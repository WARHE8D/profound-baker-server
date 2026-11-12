package com.sugarcrumbs;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator is served only on the management port (never proxied by Caddy),
 * exposes only health and info, and health reveals no component details.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@Import(TestcontainersConfiguration.class)
class ActuatorEndpointsTests {

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

    // An unmapped path currently answers 500 with a generic ApiError, because the catch-all
    // handler swallows NoResourceFoundException. #7 maps it to 404; tighten these to 404 then.

    @Test
    void actuatorIsNotServedOnTheApplicationPort() throws Exception {
        HttpResponse<String> response = get(appPort, "/actuator/health");

        assertThat(response.statusCode()).isNotEqualTo(200);
        assertThat(response.body()).doesNotContain("\"status\":\"UP\"");
    }

    @Test
    void onlyHealthAndInfoAreExposed() throws Exception {
        assertThat(get(managementPort, "/actuator/info").statusCode()).isEqualTo(200);

        for (String path : new String[]{"/actuator/env", "/actuator/beans", "/actuator/configprops",
                "/actuator/heapdump", "/actuator/loggers", "/actuator/mappings", "/actuator/flyway"}) {
            HttpResponse<String> response = get(managementPort, path);

            assertThat(response.statusCode()).as(path).isNotEqualTo(200);
            assertThat(response.body()).as(path)
                    .doesNotContain("propertySources", "\"beans\"", "\"contexts\"", "\"levels\"", "DB_PASSWORD");
        }
    }

    private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
