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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-item checkout (#14): every line keeps the name and price it had at
 * checkout, the order gets a human-readable reference, and stock is taken and
 * returned per line.
 *
 * Checkout rules (#15): configurable limits on lines and units, and no pickup in the past.
 *
 * Magic link (#18): checkout returns the guest's token once; only its hash is stored.
 *
 * <p>Each test picks its own pickup day, opened for that weekday with plenty of
 * capacity, so tests never compete for a slot in the shared database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderCheckoutTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

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
                .body(Map.of("name", "Checkout " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anOrderSnapshotsEveryLineAndRepricingLaterChangesNothing() {
        String bread = createItem("Sourdough", "6.50", null);
        String cake = createItem("Lemon cake", "24.99", null);

        ResponseEntity<Map> checkout = checkout(openDay(50), line(bread, 2), line(cake, 1));

        assertThat(checkout.getStatusCode().value()).isEqualTo(201);
        Map<String, Object> order = checkout.getBody();
        assertThat((String) order.get("reference")).matches("SC-[0-9A-HJKMNP-TV-Z]{5}");
        assertThat(order.get("subtotal")).isEqualTo(37.99);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) order.get("lines");
        assertThat(lines).extracting(l -> l.get("lineNo")).containsExactly(1, 2);
        assertThat(lines.getFirst())
                .containsEntry("itemId", bread)
                .containsEntry("itemName", "Sourdough")
                .containsEntry("unitPrice", 6.5)
                .containsEntry("quantity", 2)
                .containsEntry("lineTotal", 13.0);

        owner.patch().uri("/api/catalogue/items/{id}/price", bread)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("price", "9.00"))
                .retrieve().toBodilessEntity();
        owner.put().uri("/api/catalogue/items/{id}", bread)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Renamed loaf", "price", "9.00", "categoryId", categoryId, "leadTimeHours", 0))
                .retrieve().toBodilessEntity();

        Map<String, Object> reread = owner.get().uri("/api/orders/{id}", order.get("id")).retrieve().body(Map.class);
        assertThat(((List<Map<String, Object>>) reread.get("lines")).getFirst())
                .containsEntry("itemName", "Sourdough")
                .containsEntry("unitPrice", 6.5);
        assertThat(reread.get("subtotal")).isEqualTo(37.99);
    }

    @Test
    void stockIsTakenPerLineAndReturnedOnCancel() {
        String rolls = createItem("Cinnamon roll", "3.00", 10);
        String cookies = createItem("Cookie", "1.50", 5);

        ResponseEntity<Map> checkout = checkout(openDay(51), line(rolls, 4), line(cookies, 5));
        assertThat(checkout.getStatusCode().value()).isEqualTo(201);
        assertThat(stockOf(rolls)).isEqualTo(6);
        assertThat(stockOf(cookies)).isZero();

        owner.patch().uri("/api/orders/{id}/confirm", checkout.getBody().get("id")).retrieve().toBodilessEntity();
        owner.patch().uri("/api/orders/{id}/cancel", checkout.getBody().get("id"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "changed plans"))
                .retrieve().toBodilessEntity();

        assertThat(stockOf(rolls)).isEqualTo(10);
        assertThat(stockOf(cookies)).isEqualTo(5);
    }

    @Test
    void notEnoughStockOnAnyLineRejectsTheWholeOrder() {
        String rolls = createItem("Bagel", "2.00", 10);
        String scarce = createItem("Croquembouche", "80.00", 1);

        ResponseEntity<Map> checkout = checkout(openDay(52), line(rolls, 3), line(scarce, 2));

        assertThat(checkout.getStatusCode().value()).isEqualTo(400);
        assertThat(stockOf(rolls)).isEqualTo(10);
        assertThat(stockOf(scarce)).isEqualTo(1);
    }

    @Test
    void theSameItemTwiceIs400() {
        String bread = createItem("Rye", "5.00", null);

        ResponseEntity<Map> checkout = checkout(openDay(53), line(bread, 1), line(bread, 2));

        assertThat(checkout.getStatusCode().value()).isEqualTo(400);
        assertThat((String) checkout.getBody().get("message")).contains("only once");
    }

    @Test
    void anUnknownItemIs404AndAHiddenOneIs409() {
        String hidden = createItem("Off-menu", "5.00", null);
        owner.patch().uri("/api/catalogue/items/{id}/availability", hidden)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("available", false))
                .retrieve().toBodilessEntity();
        LocalDate day = openDay(54);

        assertThat(checkout(day, line("00000000-0000-0000-0000-00000000dead", 1)).getStatusCode().value())
                .isEqualTo(404);
        assertThat(checkout(day, line(hidden, 1)).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void anOrderWithNoLinesIs400() {
        ResponseEntity<Map> checkout = checkout(openDay(55));

        assertThat(checkout.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void theLongestLeadTimeOfAnyLineApplies() {
        String quick = createItem("Muffin", "2.00", null);
        Map<String, Object> slowItem = new HashMap<>(Map.of(
                "name", "Wedding cake", "price", "300.00", "categoryId", categoryId, "leadTimeHours", 24 * 400));
        String slow = (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON).body(slowItem)
                .retrieve().body(Map.class).get("id");

        ResponseEntity<Map> checkout = checkout(openDay(56), line(quick, 1), line(slow, 1));

        assertThat(checkout.getStatusCode().value()).isEqualTo(400);
        assertThat((String) checkout.getBody().get("message")).contains("9600 hours notice");
    }

    /** The defaults from application.properties: 20 distinct items, 100 of each (#15). */
    @Test
    @SuppressWarnings("unchecked")
    void ordersOverTheConfiguredLimitsAre400WithAClearMessage() {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(57);
        Map<String, Object>[] tooManyLines = IntStream.range(0, 21)
                .mapToObj(i -> line(UUID.randomUUID().toString(), 1)).toArray(Map[]::new);

        ResponseEntity<Map> lines = checkout(day, tooManyLines);
        ResponseEntity<Map> units = checkout(day, line(UUID.randomUUID().toString(), 101));

        assertThat(lines.getStatusCode().value()).isEqualTo(400);
        assertThat((String) lines.getBody().get("message")).isEqualTo("an order may contain at most 20 different items");
        assertThat(units.getStatusCode().value()).isEqualTo(400);
        assertThat((String) units.getBody().get("message")).isEqualTo("line 1: at most 100 of one item per order");
    }

    @Test
    void aPickupInThePastIs400ForCheckoutAndReschedule() {
        String bread = createItem("Baguette", "3.00", null);
        LocalDate day = openDay(58);
        String yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1) + "T09:00:00Z";

        ResponseEntity<Map> pastCheckout = shopper.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", yesterday,
                        "lines", List.of(line(bread, 1)),
                        "guestContact", Map.of("name", "Guest", "email", "guest@example.com", "phone", "555-0100")))
                .retrieve().toEntity(Map.class);
        assertThat(pastCheckout.getStatusCode().value()).isEqualTo(400);

        Object orderId = checkout(day, line(bread, 1)).getBody().get("id");
        ResponseEntity<Map> pastReschedule = owner.put().uri("/api/orders/{id}/schedule", orderId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("pickupAt", yesterday))
                .retrieve().toEntity(Map.class);
        assertThat(pastReschedule.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> unchanged = owner.get().uri("/api/orders/{id}", orderId).retrieve().body(Map.class);
        assertThat(unchanged.get("pickupAt")).isEqualTo(day + "T09:00:00Z");
    }

    @Test
    void checkoutReturnsAMagicLinkTokenOnceAndStoresOnlyItsHash() throws Exception {
        String bread = createItem("Token loaf", "3.00", null);
        LocalDate day = openDay(59);

        Map<String, Object> first = checkout(day, line(bread, 1)).getBody();
        Map<String, Object> second = checkout(day, line(bread, 1)).getBody();

        String token = (String) first.get("accessToken");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(first).as("order fields sit beside the token, not nested").containsKeys("id", "reference", "lines");
        assertThat(second.get("accessToken")).isNotEqualTo(token);

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT access_token_hash, access_token_expires_at FROM ordering.orders WHERE id = ?::uuid",
                first.get("id"));
        byte[] sha256 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(stored.get("access_token_hash")).isEqualTo(java.util.HexFormat.of().formatHex(sha256));
        assertThat(stored.get("access_token_expires_at")).isNull();

        Map<String, Object> ownerView = owner.get().uri("/api/orders/{id}", first.get("id")).retrieve().body(Map.class);
        assertThat(ownerView).doesNotContainKeys("accessToken", "accessTokenHash");
    }

    @Test
    void rejectingAnOrderStartsItsLinksThirtyDayExpiry() {
        String bread = createItem("Expiry loaf", "3.00", null);
        Object orderId = checkout(openDay(59), line(bread, 1)).getBody().get("id");

        owner.patch().uri("/api/orders/{id}/reject", orderId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "fully booked"))
                .retrieve().toBodilessEntity();

        Boolean thirtyDays = jdbc.queryForObject(
                "SELECT access_token_expires_at BETWEEN now() + interval '29 days 23 hours'"
                        + " AND now() + interval '30 days' FROM ordering.orders WHERE id = ?::uuid",
                Boolean.class, orderId);
        assertThat(thirtyDays).isTrue();
    }

    // ---- helpers ----

    /** Opens the weekday of {@code today + daysAhead} 09:00–12:00 with capacity for every test. */
    private LocalDate openDay(int daysAhead) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(daysAhead);
        DayOfWeek weekday = day.getDayOfWeek();
        owner.put().uri("/api/availability/working-hours/{day}", weekday)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("startTime", "09:00", "endTime", "12:00", "slotDurationMinutes", 60,
                        "maxConcurrentBookings", 100))
                .retrieve().toBodilessEntity();
        return day;
    }

    @SafeVarargs
    private ResponseEntity<Map> checkout(LocalDate day, Map<String, Object>... lines) {
        return shopper.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", day + "T09:00:00Z",
                        "lines", List.of(lines),
                        "guestContact", Map.of("name", "Guest", "email", "guest@example.com", "phone", "555-0100")))
                .retrieve().toEntity(Map.class);
    }

    private static Map<String, Object> line(String itemId, int quantity) {
        return Map.of("itemId", itemId, "quantity", quantity);
    }

    private String createItem(String name, String price, Integer stock) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "name", name, "price", price, "categoryId", categoryId, "leadTimeHours", 0));
        if (stock != null) {
            body.put("stockQuantity", stock);
        }
        return (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve().body(Map.class).get("id");
    }

    private int stockOf(String itemId) {
        return (Integer) owner.get().uri("/api/catalogue/items/{id}", itemId).retrieve().body(Map.class)
                .get("stockQuantity");
    }
}
