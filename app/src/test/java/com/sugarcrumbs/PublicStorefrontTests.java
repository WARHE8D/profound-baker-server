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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shopper surface under {@code /api/public/**} (#13), end to end and without
 * a token: the storefront shows only available items and never the Owner's
 * fields, availability is readable, and guest checkout places an order the
 * Owner then sees.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicStorefrontTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    /** No token: what any shopper's browser sends. */
    private RestClient shopper;
    private RestClient owner;
    private String categoryId;

    @BeforeEach
    void setUp() {
        OwnerFixture.ensureOwner(jdbc, passwordEncoder);
        shopper = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        owner = shopper.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + OwnerFixture.accessToken(shopper))
                .build();
        categoryId = (String) owner.post().uri("/api/catalogue/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Storefront " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    void theStorefrontListsOnlyAvailableItemsWithoutOwnerFields() {
        String shown = createItem("Sourdough", null);
        String hidden = createItem("Secret recipe", null);
        owner.patch().uri("/api/catalogue/items/{id}/availability", hidden)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("available", false))
                .retrieve().toBodilessEntity();

        ResponseEntity<Map> page = shopper.get()
                .uri("/api/public/catalogue/items?categoryId={c}", categoryId)
                .retrieve().toEntity(Map.class);

        assertThat(page.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> items = content(page);
        assertThat(items).extracting(item -> item.get("id")).containsExactly(shown);
        assertThat(items.getFirst())
                .containsKeys("name", "price", "category", "leadTimeHours", "orderable", "imageUrls")
                .doesNotContainKeys("stockQuantity", "available", "createdAt", "updatedAt");
    }

    @Test
    void aHiddenItemIsA404LikeAnUnknownOne() {
        String hidden = createItem("Withdrawn", null);
        owner.patch().uri("/api/catalogue/items/{id}/availability", hidden)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("available", false))
                .retrieve().toBodilessEntity();

        assertThat(shopper.get().uri("/api/public/catalogue/items/{id}", hidden)
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(404);
        assertThat(owner.get().uri("/api/catalogue/items/{id}", hidden)
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSoldOutItemIsShownButNotOrderable() {
        String soldOut = createItem("Last croissant", 0);

        Map<String, Object> item = shopper.get().uri("/api/public/catalogue/items/{id}", soldOut).retrieve().body(Map.class);

        assertThat(item.get("orderable")).isEqualTo(false);
        assertThat(item).doesNotContainKey("stockQuantity");
    }

    @Test
    void shoppersMaySortOnlyByWhatTheyCanSee() {
        assertThat(shopper.get().uri("/api/public/catalogue/items?sort=price,desc")
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Map> bySecret = shopper.get().uri("/api/public/catalogue/items?sort=stockQuantity")
                .retrieve().toEntity(Map.class);
        assertThat(bySecret.getStatusCode().value()).isEqualTo(400);
        assertThat(bySecret.getBody()).containsEntry("message", "items can only be sorted by name, price or leadTimeHours");
    }

    @Test
    void pageSizeIsCapped() {
        Map<?, ?> page = shopper.get().uri("/api/public/catalogue/items?size=5000").retrieve().body(Map.class);

        assertThat(page.get("size")).isEqualTo(100);
    }

    @Test
    void categoriesArePublic() {
        ResponseEntity<String> response = shopper.get().uri("/api/public/catalogue/categories")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains(categoryId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aGuestChecksOutIntoAnOpenSlotAndTheOwnerSeesTheOrder() {
        String itemId = createItem("Birthday cake", null);
        LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(40);
        owner.put().uri("/api/availability/working-hours/{day}", day.getDayOfWeek())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("startTime", "09:00", "endTime", "12:00", "slotDurationMinutes", 60,
                        "maxConcurrentBookings", 5))
                .retrieve().toBodilessEntity();

        List<Map<String, Object>> days = shopper.get().uri("/api/public/availability?date={d}", day)
                .retrieve().body(List.class);
        Map<String, Object> slot = ((List<Map<String, Object>>) days.getFirst().get("slots")).getFirst();
        assertThat(slot.get("bookable")).isEqualTo(true);

        ResponseEntity<Map> checkout = shopper.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", day + "T" + slot.get("startTime") + "Z",
                        "lines", List.of(Map.of("itemId", itemId, "quantity", 1)),
                        "guestContact", Map.of("name", "Guest", "email", "guest@example.com", "phone", "555-0100")))
                .retrieve().toEntity(Map.class);

        assertThat(checkout.getStatusCode().value()).isEqualTo(201);
        assertThat(checkout.getHeaders().getLocation()).isNull();
        assertThat(checkout.getBody()).containsEntry("status", "PENDING");
        String orderId = (String) checkout.getBody().get("id");
        assertThat(owner.get().uri("/api/orders/{id}", orderId)
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        assertThat(shopper.get().uri("/api/orders/{id}", orderId)
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void anInvalidCheckoutIs400() {
        ResponseEntity<Map> response = shopper.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("quantity", 0))
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat((List<?>) response.getBody().get("fieldErrors")).isNotEmpty();
    }

    private String createItem(String name, Integer stock) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "name", name, "price", "6.50", "categoryId", categoryId, "leadTimeHours", 0));
        if (stock != null) {
            body.put("stockQuantity", stock);
        }
        return (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(Map.class).get("id");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> content(ResponseEntity<Map> page) {
        return (List<Map<String, Object>>) page.getBody().get("content");
    }
}
