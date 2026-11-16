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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Owner's order management (#16): rejecting a pending order (its own status, apart from
 * cancelling), filtering and searching the order list, and editing a pending order's lines and
 * notes.
 *
 * <p>Each test uses its own pickup day and a unique guest name, so the shared database's other
 * orders never show up in its counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderManagementTests extends AbstractIntegrationTest {

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
                .body(Map.of("name", "Manage " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    void rejectingAPendingOrderFreesItsSlotAndStockAndKeepsTheReason() {
        String cake = createItem("Rejected cake", "20.00", 5);
        LocalDate day = openDay(70, 1);
        String guest = uniqueGuest();
        Object orderId = checkout(day, guest, line(cake, 2)).getBody().get("id");
        assertThat(stockOf(cake)).isEqualTo(3);
        assertThat(checkout(day, guest, line(cake, 1)).getStatusCode().value()).as("slot is full").isEqualTo(409);

        ResponseEntity<Map> noReason = patch("/api/orders/{id}/reject", orderId, Map.of("reason", " "));
        assertThat(noReason.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map> rejected = patch("/api/orders/{id}/reject", orderId, Map.of("reason", "Oven is out"));

        assertThat(rejected.getStatusCode().value()).isEqualTo(200);
        assertThat(rejected.getBody())
                .containsEntry("status", "REJECTED")
                .containsEntry("rejectionReason", "Oven is out");
        assertThat(rejected.getBody().get("cancellationReason")).isNull();
        assertThat(stockOf(cake)).isEqualTo(5);
        assertThat(checkout(day, guest, line(cake, 1)).getStatusCode().value()).as("slot is free again")
                .isEqualTo(201);
        assertThat(patch("/api/orders/{id}/reject", orderId, Map.of("reason", "again")).getStatusCode().value())
                .isEqualTo(400);
    }

    /** Reports count an Owner's refusal apart from a cancellation, so a pending order can only be rejected. */
    @Test
    void theOwnerCancelsAnOrderOnlyAfterConfirmingIt() {
        String bread = createItem("Cancel loaf", "4.00", null);
        Object orderId = checkout(openDay(71, 10), uniqueGuest(), line(bread, 1)).getBody().get("id");

        ResponseEntity<Map> pending = patch("/api/orders/{id}/cancel", orderId, Map.of("reason", "no"));
        assertThat(pending.getStatusCode().value()).isEqualTo(400);
        assertThat((String) pending.getBody().get("message")).contains("rejected, not cancelled");

        patch("/api/orders/{id}/confirm", orderId, null);
        ResponseEntity<Map> cancelled = patch("/api/orders/{id}/cancel", orderId, Map.of("reason", "flooded"));

        assertThat(cancelled.getBody())
                .containsEntry("status", "CANCELLED")
                .containsEntry("cancellationReason", "flooded");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theOrderListFiltersByRangeStatusesAndSearch() {
        String bread = createItem("Search loaf", "4.00", null);
        LocalDate day = openDay(72, 10);
        String tag = "Zq" + Long.toString(System.nanoTime(), 36);
        Map<String, Object> pending = checkout(day, tag + " Pending", line(bread, 1)).getBody();
        Map<String, Object> confirmed = checkout(day, tag + " Confirmed", line(bread, 1)).getBody();
        Map<String, Object> rejected = checkout(day, tag + " Rejected", line(bread, 1)).getBody();
        patch("/api/orders/{id}/confirm", confirmed.get("id"), null);
        patch("/api/orders/{id}/reject", rejected.get("id"), Map.of("reason", "no"));

        assertThat(ids("/api/orders?q={q}", tag.toUpperCase()))
                .as("case-insensitive, on the guest name")
                .containsExactlyInAnyOrder(pending.get("id"), confirmed.get("id"), rejected.get("id"));
        assertThat(ids("/api/orders?q={q}&status=PENDING&status=CONFIRMED", tag))
                .containsExactlyInAnyOrder(pending.get("id"), confirmed.get("id"));
        assertThat(ids("/api/orders?q={q}", ((String) pending.get("reference")).toLowerCase()))
                .containsExactly(pending.get("id"));
        assertThat(ids("/api/orders?q={q}&from={from}", tag, day.plusDays(1) + "T00:00:00Z")).isEmpty();
        assertThat(ids("/api/orders?q={q}&to={to}", tag, day.plusDays(1) + "T00:00:00Z")).hasSize(3);

        String dayStart = day + "T00:00:00Z";
        String dayEnd = day.plusDays(1) + "T00:00:00Z";
        assertThat(ids("/api/orders?q={q}&from={from}&to={to}", "%", dayStart, dayEnd))
                .as("a % in the search is a literal, not a wildcard").isEmpty();
        assertThat(owner.get().uri("/api/orders?from={from}&to={to}", dayEnd, dayStart)
                .retrieve().toEntity(Map.class).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @SuppressWarnings("unchecked")
    void editingAPendingOrdersLinesKeepsAgreedPricesAndMovesStockByTheDifference() {
        String bread = createItem("Edit loaf", "6.50", 10);
        String roll = createItem("Edit roll", "3.00", 5);
        Object orderId = checkout(openDay(73, 10), uniqueGuest(), line(bread, 2)).getBody().get("id");
        owner.patch().uri("/api/catalogue/items/{id}/price", bread)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("price", "9.00"))
                .retrieve().toBodilessEntity();

        ResponseEntity<Map> edited = put("/api/orders/{id}/lines", orderId,
                Map.of("lines", List.of(line(bread, 4), line(roll, 1))));

        assertThat(edited.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) edited.getBody().get("lines");
        assertThat(lines).extracting(l -> l.get("itemId")).containsExactly(bread, roll);
        assertThat(lines.getFirst()).containsEntry("unitPrice", 6.5).containsEntry("quantity", 4);
        assertThat(lines.get(1)).containsEntry("unitPrice", 3.0).containsEntry("lineNo", 2);
        assertThat(edited.getBody().get("subtotal")).isEqualTo(29.0);
        assertThat(stockOf(bread)).isEqualTo(6);
        assertThat(stockOf(roll)).isEqualTo(4);

        ResponseEntity<Map> tooMany = put("/api/orders/{id}/lines", orderId, Map.of("lines", List.of(line(roll, 6))));
        assertThat(tooMany.getStatusCode().value()).as("not enough rolls").isEqualTo(400);
        assertThat(stockOf(roll)).isEqualTo(4);

        Map<String, Object> rollsOnly = put("/api/orders/{id}/lines", orderId,
                Map.of("lines", List.of(line(roll, 2)))).getBody();
        assertThat((List<Map<String, Object>>) rollsOnly.get("lines")).singleElement()
                .satisfies(l -> assertThat(l).containsEntry("itemId", roll).containsEntry("lineNo", 2));
        assertThat(stockOf(bread)).isEqualTo(10);
        assertThat(stockOf(roll)).isEqualTo(3);

        patch("/api/orders/{id}/confirm", orderId, null);
        assertThat(put("/api/orders/{id}/lines", orderId, Map.of("lines", List.of(line(roll, 1))))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void aPendingOrdersNotesCanBeReplacedButNotOnceConfirmed() {
        String bread = createItem("Notes loaf", "4.00", null);
        Object orderId = checkout(openDay(74, 10), uniqueGuest(), line(bread, 1)).getBody().get("id");

        ResponseEntity<Map> noted = put("/api/orders/{id}/notes", orderId, Map.of("notes", "Write 'Happy 40th'"));
        assertThat(noted.getBody()).containsEntry("notes", "Write 'Happy 40th'");

        patch("/api/orders/{id}/confirm", orderId, null);
        assertThat(put("/api/orders/{id}/notes", orderId, Map.of("notes", "changed")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(owner.get().uri("/api/orders/{id}", orderId).retrieve().body(Map.class))
                .containsEntry("notes", "Write 'Happy 40th'");
    }

    // ---- helpers ----

    private LocalDate openDay(int daysAhead, int capacity) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(daysAhead);
        owner.put().uri("/api/availability/working-hours/{day}", day.getDayOfWeek())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("startTime", "09:00", "endTime", "12:00", "slotDurationMinutes", 60,
                        "maxConcurrentBookings", capacity))
                .retrieve().toBodilessEntity();
        return day;
    }

    @SafeVarargs
    private ResponseEntity<Map> checkout(LocalDate day, String guestName, Map<String, Object>... lines) {
        return shopper.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", day + "T09:00:00Z",
                        "lines", List.of(lines),
                        "guestContact", Map.of("name", guestName, "email", "guest@example.com", "phone", "555-0100")))
                .retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Object> ids(String uri, Object... variables) {
        ResponseEntity<Map> page = owner.get().uri(uri, variables).retrieve().toEntity(Map.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        return ((List<Map<String, Object>>) page.getBody().get("content")).stream()
                .map(order -> order.get("id")).toList();
    }

    private ResponseEntity<Map> patch(String uri, Object id, Map<String, Object> body) {
        RestClient.RequestBodySpec request = owner.patch().uri(uri, id);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(Map.class);
    }

    private ResponseEntity<Map> put(String uri, Object id, Map<String, Object> body) {
        return owner.put().uri(uri, id).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(Map.class);
    }

    private static String uniqueGuest() {
        return "Guest " + System.nanoTime();
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
