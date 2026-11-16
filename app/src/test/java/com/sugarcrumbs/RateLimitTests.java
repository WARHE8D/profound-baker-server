package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-IP rate limits on login and checkout (#13). This context gets small limits
 * of its own (the other tests run with limits too high to hit); each test spends
 * a bucket of its own, since every request here comes from 127.0.0.1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.login.capacity=3",
        "app.rate-limit.login.period=1h",
        "app.rate-limit.checkout.capacity=2",
        "app.rate-limit.checkout.period=1h",
        "app.rate-limit.magic-link.capacity=2",
        "app.rate-limit.magic-link.period=1h"
})
class RateLimitTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private RestClient http;

    @BeforeEach
    void setUp() {
        OwnerFixture.ensureOwner(jdbc, passwordEncoder);
        http = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
    }

    /**
     * Login and checkout have separate buckets, so this one test covers login completely: the
     * limit holds even for the right password, it counts failures, and it can't be dodged by
     * spelling the path differently.
     */
    @Test
    void loginIsLimitedPerClientWithA429AndRetryAfter() {
        List<Integer> statuses = new ArrayList<>();
        statuses.add(login("wrong password").getStatusCode().value());
        statuses.add(login("wrong password").getStatusCode().value());
        statuses.add(login(OwnerFixture.PASSWORD).getStatusCode().value());
        assertThat(statuses).containsExactly(401, 401, 200);

        ResponseEntity<Map> limited = login(OwnerFixture.PASSWORD);

        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getBody())
                .containsEntry("status", 429)
                .containsEntry("path", "/api/auth/login")
                .doesNotContainKey("accessToken");
        assertThat(Long.parseLong(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(1L, 3600L);

        // Spring MVC would route a percent-encoded spelling to login too, so it shares the bucket.
        ResponseEntity<String> encoded = http.post()
                .uri(URI.create("http://localhost:" + port + "/api/auth/%6Cogin"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", OwnerFixture.EMAIL, "password", OwnerFixture.PASSWORD))
                .retrieve().toEntity(String.class);
        assertThat(encoded.getStatusCode().value()).isEqualTo(429);
        assertThat(encoded.getBody()).doesNotContain("accessToken");
    }

    @Test
    void checkoutIsLimitedPerClient() {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            statuses.add(http.post().uri("/api/public/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of())
                    .retrieve().toBodilessEntity().getStatusCode().value());
        }

        // Invalid checkouts still count: the bucket is spent before the body is even read.
        assertThat(statuses).containsExactly(400, 400, 429);
    }

    /** Viewing and cancelling share a bucket, so switching between them doesn't buy more guesses. */
    @Test
    void magicLinkRoutesShareOneLimitPerClient() {
        String token = "A".repeat(43);
        int view = http.get().uri("/api/public/orders/by-token/{token}", token)
                .retrieve().toBodilessEntity().getStatusCode().value();
        int cancel = http.post().uri("/api/public/orders/by-token/{token}/cancel", token)
                .retrieve().toBodilessEntity().getStatusCode().value();
        int limited = http.get().uri("/api/public/orders/by-token/{token}", token)
                .retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(List.of(view, cancel, limited)).containsExactly(404, 404, 429);
    }

    @Test
    void otherEndpointsAreNotLimited() {
        for (int i = 0; i < 10; i++) {
            assertThat(http.get().uri("/api/public/catalogue/categories")
                    .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        }
    }

    private ResponseEntity<Map> login(String password) {
        return http.post().uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", OwnerFixture.EMAIL, "password", password))
                .retrieve().toEntity(Map.class);
    }
}
