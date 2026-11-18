package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every response carries an {@code X-Request-Id} (#26), errors included, and every log line written
 * while handling the request carries that id and the request's trace id ({@link RequestIdFilter}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RequestIdTests extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void aRequestWithoutAnIdGetsANewOne() throws Exception {
        String first = send(get("/api/public/catalogue/items")).headers().firstValue("X-Request-Id").orElseThrow();
        String second = send(get("/api/public/catalogue/items")).headers().firstValue("X-Request-Id").orElseThrow();

        assertThat(UUID.fromString(first)).isNotEqualTo(UUID.fromString(second));
    }

    @Test
    void aPlausibleIncomingIdIsKept() throws Exception {
        HttpResponse<String> response = send(get("/api/public/catalogue/items").header("X-Request-Id", "spa-7f3k2-abcdef"));

        assertThat(response.headers().firstValue("X-Request-Id")).contains("spa-7f3k2-abcdef");
    }

    /** It ends up in log lines: anything that could forge or break one is replaced, not echoed. */
    @Test
    void anImplausibleIncomingIdIsReplaced() throws Exception {
        for (String sent : new String[] {"short", "x".repeat(65), "abc def ghi", "abcdefgh\"}{", "abcdefgh%0Afake"}) {
            HttpResponse<String> response = send(get("/api/public/catalogue/items").header("X-Request-Id", sent));

            String id = response.headers().firstValue("X-Request-Id").orElseThrow();
            assertThat(id).as(sent).isNotEqualTo(sent);
            assertThat(UUID.fromString(id)).isNotNull();
        }
    }

    @Test
    void errorsCarryAnIdToo() throws Exception {
        assertThat(send(get("/api/orders")).statusCode()).isEqualTo(401);
        assertThat(send(get("/api/orders")).headers().firstValue("X-Request-Id")).isPresent();
        assertThat(send(get("/api/public/nothing-here")).headers().firstValue("X-Request-Id")).isPresent();
    }

    /** "Owner login failed" is logged at INFO inside the request: the line names the request and its trace. */
    @Test
    void logLinesWrittenDuringARequestCarryItsRequestAndTraceIds(CapturedOutput output) throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Request-Id", "login-check-0001")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}")));

        assertThat(response.statusCode()).isEqualTo(401);
        String line = output.getOut().lines()
                .filter(l -> l.contains("Owner login failed") && l.contains("login-check-0001"))
                .findFirst().orElseThrow(() -> new AssertionError("no log line with the request id in:\n" + output));
        // Plain pattern or JSON, depending on which test context last set up logging (ProfileStartupTests
        // boots the prod profile): either way the line holds the trace id and span id as well.
        assertThat(line).containsPattern("\\b[0-9a-f]{32}\\b").containsPattern("\\b[0-9a-f]{16}\\b");
        assertThat(line).doesNotContain("nobody@example.com");
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(uri(path)).GET();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
