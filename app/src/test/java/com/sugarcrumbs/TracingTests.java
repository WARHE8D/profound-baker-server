package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What becomes a trace (#26, {@link ObservabilityConfig}): an API request is one trace with Spring
 * Security's steps inside it, while health checks and Prometheus scrapes on the management port
 * produce none.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class TracingTests extends AbstractIntegrationTest {

    /** Observations recorded while a test runs: the shared registry keeps the handler, so it can be paused. */
    private static final Recorder recorder = new Recorder();
    private static boolean registered;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    ObservationRegistry observationRegistry;

    @LocalServerPort
    int appPort;

    @LocalManagementPort
    int managementPort;

    @BeforeEach
    void record() {
        if (!registered) {
            observationRegistry.observationConfig().observationHandler(recorder);
            registered = true;
        }
        recorder.started.clear();
        recorder.recording = true;
    }

    @AfterEach
    void stopRecording() {
        recorder.recording = false;
    }

    /** A scrape reads baker_orders_pending from the database: that query is not a trace either. */
    @Test
    void healthChecksAndScrapesOnTheManagementPortAreNotTraced() throws Exception {
        get(managementPort, "/actuator/health");
        get(managementPort, "/actuator/prometheus");

        assertThat(recorder.started).as("observations started").isEmpty();
    }

    @Test
    void anApiRequestIsOneTraceWithItsSecurityStepsAndDatabaseCallsInside() throws Exception {
        get(appPort, "/api/public/catalogue/items");

        List<Observation.Context> roots = recorder.started.stream()
                .filter(context -> context.getParentObservation() == null).toList();
        assertThat(roots).extracting(Observation.Context::getName).containsExactly("http.server.requests");
        assertThat(recorder.started).extracting(Observation.Context::getName)
                .anyMatch(name -> name.startsWith("spring.security."))
                .contains("jdbc.connection", "jdbc.query")
                .as("no span per ResultSet row (jdbc.includes)").doesNotContain("jdbc.result-set");
    }

    /** Spans travel to Tempo and are kept for a week: a guest's details must never be in one. */
    @Test
    void databaseSpansCarryTheQueryButNeverItsParameterValues() throws Exception {
        String marker = "trace-secret-" + System.nanoTime();
        get(appPort, "/api/public/catalogue/items?search=" + marker);

        List<Observation.Context> queries = recorder.started.stream()
                .filter(context -> context.getName().equals("jdbc.query")).toList();
        assertThat(queries).isNotEmpty();
        assertThat(queries).allSatisfy(context -> {
            String keyValues = context.getAllKeyValues().toString();
            assertThat(keyValues).contains("select").doesNotContain(marker);
        });
    }

    private void get(int port, String path) throws Exception {
        HttpResponse<Void> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
    }

    private static final class Recorder implements ObservationHandler<Observation.Context> {

        final List<Observation.Context> started = new CopyOnWriteArrayList<>();
        volatile boolean recording;

        @Override
        public void onStart(Observation.Context context) {
            if (recording) {
                started.add(context);
            }
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}
