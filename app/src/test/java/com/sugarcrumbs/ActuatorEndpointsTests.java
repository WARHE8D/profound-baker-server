package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator is served only on the management port (never proxied by Caddy),
 * exposes only health, info and prometheus, and health reveals no component details.
 * Every metric the Grafana dashboards and alert rules query (#26) is one the app really exports.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
class ActuatorEndpointsTests extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int appPort;

    @LocalManagementPort
    int managementPort;

    @Test
    void healthAndProbesAreUpOnTheManagementPort() throws Exception {
        for (String path : new String[]{"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            HttpResponse<String> response = get(managementPort, path);

            assertThat(response.statusCode()).as(path).isEqualTo(200);
            assertThat(response.body()).as(path).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void healthShowsNoComponentDetails() throws Exception {
        assertThat(get(managementPort, "/actuator/health").body())
                .doesNotContain("components")
                .doesNotContain("db");
    }

    @Test
    void actuatorIsNotServedOnTheApplicationPort() throws Exception {
        HttpResponse<String> response = get(appPort, "/actuator/health");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"path\":\"/actuator/health\"").doesNotContain("\"status\":\"UP\"");
    }

    @Test
    void onlyHealthInfoAndPrometheusAreExposed() throws Exception {
        assertThat(get(managementPort, "/actuator/info").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);

        for (String path : new String[]{"/actuator/env", "/actuator/beans", "/actuator/configprops",
                "/actuator/heapdump", "/actuator/loggers", "/actuator/mappings", "/actuator/flyway",
                "/actuator/metrics", "/actuator/modulith", "/actuator/threaddump"}) {
            HttpResponse<String> response = get(managementPort, path);

            assertThat(response.statusCode()).as(path).isEqualTo(404);
            assertThat(response.body()).as(path)
                    .doesNotContain("propertySources", "\"beans\"", "\"contexts\"", "\"levels\"", "DB_PASSWORD");
        }
    }

    @Test
    void prometheusIsNotServedOnTheApplicationPort() throws Exception {
        HttpResponse<String> response = get(appPort, "/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("jvm_memory_used_bytes");
    }

    /**
     * The business metrics read 0 from the start rather than appearing on first use, and request
     * timings come with histogram buckets, so the dashboards can compute percentiles.
     */
    @Test
    void prometheusExportsTheBusinessMetricsAndRequestHistograms() throws Exception {
        get(appPort, "/api/public/catalogue/items");

        assertThat(get(managementPort, "/actuator/prometheus").body())
                .contains("baker_orders_total{application=\"baker\",status=\"PENDING\"}")
                .contains("baker_orders_total{application=\"baker\",status=\"REJECTED\"}")
                .contains("baker_orders_pending{application=\"baker\"}")
                .contains("baker_stale_orders_cancelled_total{")
                .contains("baker_auth_login_failures_total{")
                .contains("baker_invoices_paid_amount_total{")
                .contains("baker_invoices_refunded_amount_total{")
                .containsPattern("http_server_requests_seconds_bucket\\{[^}]*uri=\"/api/public/catalogue/items\"");
    }

    /**
     * A renamed metric would leave a dashboard panel or an alert silently empty. Every app metric they
     * query must be in a real scrape; the backup job's metric comes from the backup container instead,
     * and Resilience4j has no circuit breakers until a module is extracted (CLAUDE.md §3).
     */
    @Test
    void everyAppMetricTheDashboardsAndAlertsQueryIsExported() throws Exception {
        get(appPort, "/api/public/catalogue/items");
        System.gc(); // GC pause timings only exist once a collection has happened.
        String scrape = get(managementPort, "/actuator/prometheus").body();

        Path infra = repoRoot().resolve("infra");
        Pattern appMetric = Pattern.compile(
                "\\b((?:baker|http_server_requests|jvm|process|system|hikaricp)_[a-z0-9_]+)\\b");
        List<Path> files = new ArrayList<>(List.of(infra.resolve("prometheus/alerts.yml")));
        try (Stream<Path> dashboards = Files.list(infra.resolve("grafana/dashboards"))) {
            dashboards.forEach(files::add);
        }
        Set<String> queried = new TreeSet<>();
        for (Path file : files) {
            appMetric.matcher(Files.readString(file)).results().map(m -> m.group(1))
                    .filter(name -> !name.startsWith("baker_backup_"))
                    .forEach(queried::add);
        }

        assertThat(queried).contains("baker_orders_total", "baker_orders_pending", "http_server_requests_seconds_bucket");
        assertThat(queried).allSatisfy(name -> assertThat(scrape).as(name).containsPattern("(?m)^" + name + "[{ ]"));
    }

    private static Path repoRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("infra"))) {
                return dir;
            }
        }
        throw new IllegalStateException("infra/ not found above " + Path.of("").toAbsolutePath());
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
