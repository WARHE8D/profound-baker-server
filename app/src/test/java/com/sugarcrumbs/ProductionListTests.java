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
 * The daily production list (#22) against Postgres: only the day's confirmed and in-progress
 * orders count, summed per item. The day is used by no other test, so the list holds only the
 * orders placed here (the bakery's zone is UTC in tests).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductionListTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

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
                .body(Map.of("name", "Production " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onlyTheDaysAcceptedOrdersAreSummedPerItem() {
        LocalDate day = openDay(120);
        LocalDate nextDay = day.plusDays(7);
        String bread = createItem("Prod sourdough");
        String rolls = createItem("Prod rolls");

        confirm(placeOrder(day, "09", Map.of(bread, 2, rolls, 6)));
        String inProgress = placeOrder(day, "10", Map.of(bread, 3));
        confirm(inProgress);
        owner.patch().uri("/api/orders/{id}/start-preparation", inProgress).retrieve().toBodilessEntity();
        placeOrder(day, "11", Map.of(bread, 50));                       // pending: not accepted yet
        String rejected = placeOrder(day, "11", Map.of(rolls, 40));
        owner.patch().uri("/api/orders/{id}/reject", rejected).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "no")).retrieve().toBodilessEntity();
        confirm(placeOrder(nextDay, "09", Map.of(bread, 9)));            // another day

        ResponseEntity<Map> response = owner.get().uri("/api/dashboard/production?date={date}", day)
                .retrieve().toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> list = response.getBody();
        assertThat(list).containsEntry("date", day.toString()).containsEntry("orderCount", 2);
        assertThat((List<Map<String, Object>>) list.get("items")).containsExactly(
                Map.of("itemId", rolls, "itemName", "Prod rolls", "quantity", 6, "orderCount", 1),
                Map.of("itemId", bread, "itemName", "Prod sourdough", "quantity", 5, "orderCount", 2));
    }

    @Test
    void aMalformedDateIs400() {
        assertThat(owner.get().uri("/api/dashboard/production?date=tomorrow").retrieve().toEntity(Map.class)
                .getStatusCode().value()).isEqualTo(400);
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

    private String placeOrder(LocalDate day, String hour, Map<String, Integer> quantities) {
        List<Map<String, Object>> lines = quantities.entrySet().stream()
                .map(e -> Map.<String, Object>of("itemId", e.getKey(), "quantity", e.getValue())).toList();
        ResponseEntity<Map> placed = guest.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", day + "T" + hour + ":00:00Z",
                        "lines", lines,
                        "guestContact", Map.of("name", "Pru", "email", "pru@example.com", "phone", "555-0100")))
                .retrieve().toEntity(Map.class);
        assertThat(placed.getStatusCode().value()).isEqualTo(201);
        return (String) placed.getBody().get("id");
    }

    private void confirm(String orderId) {
        owner.patch().uri("/api/orders/{id}/confirm", orderId).retrieve().toBodilessEntity();
    }

    private String createItem(String name) {
        return (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HashMap<>(Map.of("name", name, "price", "3.00", "categoryId", categoryId,
                        "leadTimeHours", 0)))
                .retrieve().body(Map.class).get("id");
    }
}
