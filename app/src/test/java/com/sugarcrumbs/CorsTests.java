package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CORS allows only the configured SPA origin (#13, CLAUDE.md §5), never in
 * credentials mode, and refuses wildcard configuration outright.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.cors.allowed-origins=https://shop.sugarcrumbs.test")
class CorsTests extends AbstractIntegrationTest {

    private static final String SPA = "https://shop.sugarcrumbs.test";
    private static final String EVIL = "https://evil.example";

    @LocalServerPort
    int port;

    private RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    @Test
    void theSpaMayPreflightAnOwnerCallWithABearerToken() {
        ResponseEntity<Void> preflight = preflight(SPA, "PUT", "/api/owner/profile", "authorization,content-type");

        assertThat(preflight.getStatusCode().value()).isEqualTo(200);
        HttpHeaders headers = preflight.getHeaders();
        assertThat(headers.getAccessControlAllowOrigin()).isEqualTo(SPA);
        assertThat(headers.getAccessControlAllowMethods()).contains(HttpMethod.PUT);
        assertThat(headers.getAccessControlAllowHeaders())
                .map(String::toLowerCase).contains("authorization", "content-type");
        // No credentials mode: nothing the browser holds on its own is ever sent cross-site.
        assertThat(headers.getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    @Test
    void theSpaCanReadPublicResponsesAndRateLimitHeaders() {
        ResponseEntity<String> response = http.get().uri("/api/public/catalogue/categories")
                .header(HttpHeaders.ORIGIN, SPA)
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(SPA);
        assertThat(response.getHeaders().getAccessControlExposeHeaders())
                .contains("Retry-After", "X-RateLimit-Remaining");
    }

    @Test
    void anotherOriginIsRefused() {
        ResponseEntity<Void> preflight = preflight(EVIL, "POST", "/api/auth/login", "content-type");
        assertThat(preflight.getStatusCode().value()).isEqualTo(403);
        assertThat(preflight.getHeaders().getAccessControlAllowOrigin()).isNull();

        ResponseEntity<String> simple = http.get().uri("/api/public/catalogue/categories")
                .header(HttpHeaders.ORIGIN, EVIL)
                .retrieve().toEntity(String.class);
        assertThat(simple.getStatusCode().value()).isEqualTo(403);
        assertThat(simple.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void unlistedRequestHeadersAreRefused() {
        assertThat(preflight(SPA, "GET", "/api/owner/profile", "x-custom-header").getStatusCode().value())
                .isEqualTo(403);
    }

    @Test
    void aWildcardOriginIsRefusedAtStartup() {
        assertThatThrownBy(() -> new WebEdgeConfig().corsConfigurationSource("https://shop.test, *"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcards");
        assertThatThrownBy(() -> new WebEdgeConfig().corsConfigurationSource("https://*.sugarcrumbs.test"))
                .isInstanceOf(IllegalStateException.class);
    }

    private ResponseEntity<Void> preflight(String origin, String method, String path, String headers) {
        return http.options().uri(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers)
                .retrieve().toBodilessEntity();
    }
}
