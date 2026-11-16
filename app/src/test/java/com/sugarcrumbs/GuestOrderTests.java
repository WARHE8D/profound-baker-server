package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import com.sugarcrumbs.ordering.application.ExpiredAccessTokenPurgeJob;
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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guest's magic link (#19): viewing and cancelling their own order with the token from
 * checkout, the cutoff before pickup, and every failure being the same 404 (malformed, unknown,
 * expired, purged), with no owner credentials involved at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GuestOrderTests extends AbstractIntegrationTest {

    /** Well-formed (43 base64url characters) but issued to no order. */
    private static final String UNKNOWN_TOKEN = "A".repeat(43);

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ExpiredAccessTokenPurgeJob purgeJob;

    private RestClient guest;
    private RestClient owner;
    private String categoryId;

    @BeforeEach
    void setUp() {
        OwnerFixture.ensureOwner(jdbc, passwordEncoder);
        guest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        owner = guest.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + OwnerFixture.accessToken(guest))
                .build();
        categoryId = (String) owner.post().uri("/api/catalogue/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Guest " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    void theTokenOpensTheGuestsOwnOrderAndNothingElse() {
        Map<String, Object> placed = checkout(openDay(90), createItem("Link loaf", 5), 2);
        String token = (String) placed.get("accessToken");

        ResponseEntity<Map> view = guest.get().uri("/api/public/orders/by-token/{token}", token)
                .retrieve().toEntity(Map.class);

        assertThat(view.getStatusCode().value()).isEqualTo(200);
        assertThat(view.getBody())
                .containsEntry("reference", placed.get("reference"))
                .containsEntry("status", "PENDING")
                .containsEntry("subtotal", 8.0)
                .containsKeys("lines", "guestContact", "placedAt", "cancellableUntil")
                .doesNotContainKeys("id", "accessToken", "accessTokenHash", "updatedAt");
    }

    @Test
    void everyTokenThatOpensNothingIsTheSame404() {
        String malformed = "not-a-token";
        String expired = (String) checkout(openDay(91), createItem("Expired loaf", null), 1).get("accessToken");
        jdbc.update("""
                UPDATE ordering.orders SET status = 'CANCELLED', access_token_expires_at = now() - interval '1 second'
                WHERE access_token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')
                """, expired);

        for (String token : List.of(malformed, UNKNOWN_TOKEN, expired)) {
            ResponseEntity<Map> view = guest.get().uri("/api/public/orders/by-token/{token}", token)
                    .retrieve().toEntity(Map.class);
            ResponseEntity<Map> cancel = guest.post().uri("/api/public/orders/by-token/{token}/cancel", token)
                    .retrieve().toEntity(Map.class);

            assertThat(view.getStatusCode().value()).as(token).isEqualTo(404);
            assertThat(cancel.getStatusCode().value()).as(token).isEqualTo(404);
            assertThat(view.getBody().get("message")).isEqualTo("no order found for this link; it may have expired");
        }
    }

    @Test
    void aGuestCancelsTheirOrderAndItsSlotAndStockComeBack() {
        LocalDate day = openDay(92);
        String cake = createItem("Cancel cake", 3);
        Map<String, Object> placed = checkout(day, cake, 2);
        assertThat(stockOf(cake)).isOne();

        ResponseEntity<Map> cancelled = guest.post()
                .uri("/api/public/orders/by-token/{token}/cancel", placed.get("accessToken"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "plans changed"))
                .retrieve().toEntity(Map.class);

        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody())
                .containsEntry("status", "CANCELLED")
                .containsEntry("cancellationReason", "plans changed");
        assertThat(cancelled.getBody().get("cancellableUntil")).isNull();
        assertThat(stockOf(cake)).isEqualTo(3);
        // The link keeps working for a while, so the guest can see how their order ended.
        assertThat(guest.get().uri("/api/public/orders/by-token/{token}", placed.get("accessToken"))
                .retrieve().toEntity(Map.class).getBody()).containsEntry("status", "CANCELLED");
        assertThat(guest.post().uri("/api/public/orders/by-token/{token}/cancel", placed.get("accessToken"))
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void insideTheCutoffTheGuestIsSentToTheBakery() {
        Map<String, Object> placed = checkout(openDay(93), createItem("Late loaf", null), 1);
        jdbc.update("UPDATE ordering.orders SET pickup_at = now() + interval '2 hours' WHERE id = ?::uuid",
                placed.get("id"));

        ResponseEntity<Map> late = guest.post()
                .uri("/api/public/orders/by-token/{token}/cancel", placed.get("accessToken"))
                .retrieve().toEntity(Map.class);

        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat((String) late.getBody().get("message")).contains("24 hours before pickup");
        assertThat(owner.get().uri("/api/orders/{id}", placed.get("id")).retrieve().body(Map.class))
                .containsEntry("status", "PENDING");
    }

    @Test
    void thePurgeJobForgetsExpiredLinksOnly() {
        LocalDate day = openDay(94);
        String bread = createItem("Purge loaf", null);
        Map<String, Object> expired = checkout(day, bread, 1);
        Map<String, Object> live = checkout(day, bread, 1);
        jdbc.update("""
                UPDATE ordering.orders SET status = 'CANCELLED', access_token_expires_at = now() - interval '1 day'
                WHERE id = ?::uuid
                """, expired.get("id"));

        purgeJob.purgeExpiredAccessTokens();

        Map<String, Object> purged = jdbc.queryForMap(
                "SELECT access_token_hash, access_token_expires_at FROM ordering.orders WHERE id = ?::uuid",
                expired.get("id"));
        assertThat(purged).containsEntry("access_token_hash", null).containsEntry("access_token_expires_at", null);
        assertThat(guest.get().uri("/api/public/orders/by-token/{token}", live.get("accessToken"))
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(200);
    }

    // ---- helpers ----

    private LocalDate openDay(int daysAhead) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(daysAhead);
        owner.put().uri("/api/availability/working-hours/{day}", day.getDayOfWeek())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("startTime", "09:00", "endTime", "12:00", "slotDurationMinutes", 60,
                        "maxConcurrentBookings", 10))
                .retrieve().toBodilessEntity();
        return day;
    }

    private Map<String, Object> checkout(LocalDate day, String itemId, int quantity) {
        ResponseEntity<Map> response = guest.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", day + "T09:00:00Z",
                        "lines", List.of(Map.of("itemId", itemId, "quantity", quantity)),
                        "guestContact", Map.of("name", "Gus", "email", "gus@example.com", "phone", "555-0100")))
                .retrieve().toEntity(Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody();
    }

    private String createItem(String name, Integer stock) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "name", name, "price", "4.00", "categoryId", categoryId, "leadTimeHours", 0));
        if (stock != null) {
            body.put("stockQuantity", stock);
        }
        return (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(Map.class).get("id");
    }

    private int stockOf(String itemId) {
        return (Integer) owner.get().uri("/api/catalogue/items/{id}", itemId).retrieve().body(Map.class)
                .get("stockQuantity");
    }
}
