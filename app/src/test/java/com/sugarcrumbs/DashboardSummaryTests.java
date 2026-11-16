package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard summary (#23) against Postgres, including the native monthly revenue query. The
 * database is shared with other test classes, so each check compares the summary before and after
 * this test's own changes rather than absolute numbers. The bakery's zone is UTC in tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardSummaryTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private RestClient guest;
    private RestClient owner;
    private String bread;

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
        String categoryId = (String) owner.post().uri("/api/catalogue/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Summary " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
        bread = (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HashMap<>(Map.of("name", "Summary loaf", "price", "12.00", "categoryId", categoryId,
                        "leadTimeHours", 0)))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingAndTodaysPickupsCountWhatTheyShould() {
        LocalDate day = openDay(130);
        Map<String, Object> before = summary(12);

        String pending = placeOrder(day);
        String collectedToday = placeOrder(day);
        String toCollectToday = placeOrder(day);
        confirm(collectedToday);
        confirm(toCollectToday);
        owner.patch().uri("/api/orders/{id}/fulfill", collectedToday).retrieve().toBodilessEntity();
        // Checkout only takes future slots; moving two orders onto today is the way to get pickups "today".
        // Today in UTC (the bakery's zone here), not in the database session's zone.
        jdbc.update("UPDATE ordering.orders"
                + " SET pickup_at = (date_trunc('day', now() AT TIME ZONE 'UTC') + interval '1 hour') AT TIME ZONE 'UTC'"
                + " WHERE id IN (?::uuid, ?::uuid)", collectedToday, toCollectToday);

        Map<String, Object> after = summary(12);

        assertThat(after.get("today")).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(count(after, "pendingOrders") - count(before, "pendingOrders")).isEqualTo(1);
        Map<String, Object> pickupsBefore = (Map<String, Object>) before.get("todaysPickups");
        Map<String, Object> pickupsAfter = (Map<String, Object>) after.get("todaysPickups");
        assertThat(count(pickupsAfter, "total") - count(pickupsBefore, "total")).isEqualTo(2);
        assertThat(count(pickupsAfter, "toCollect") - count(pickupsBefore, "toCollect")).isEqualTo(1);
        assertThat(pending).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void revenueIsWhatPaidInvoicesBroughtInAfterDiscountsAndNotRefunds() {
        LocalDate day = openDay(131);
        Map<String, Object> before = thisMonth(summary(3));

        Object kept = invoiceFor(placeOrder(day));
        Object refunded = invoiceFor(placeOrder(day));
        Object unpaid = invoiceFor(placeOrder(day));
        patch("/api/invoices/{id}/discount", kept, Map.of("amount", "2.00"));
        patch("/api/invoices/{id}/pay", kept, Map.of("method", "CASH"));
        patch("/api/invoices/{id}/pay", refunded, Map.of("method", "CASH"));
        patch("/api/invoices/{id}/refund", refunded, null);

        Map<String, Object> summary = summary(3);
        Map<String, Object> after = thisMonth(summary);

        List<Map<String, Object>> months = (List<Map<String, Object>>) summary.get("revenueByMonth");
        assertThat(months).extracting(m -> m.get("month")).containsExactly(
                YearMonth.now(ZoneOffset.UTC).minusMonths(2).toString(),
                YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString(),
                YearMonth.now(ZoneOffset.UTC).toString());
        assertThat(((Number) after.get("revenue")).doubleValue() - ((Number) before.get("revenue")).doubleValue())
                .isEqualTo(10.0);
        assertThat(count(after, "paidInvoices") - count(before, "paidInvoices")).isEqualTo(1);
        assertThat(unpaid).isNotNull();
    }

    @Test
    void theRevenueRangeIsChecked() {
        assertThat(owner.get().uri("/api/dashboard/summary?months=25").retrieve().toEntity(Map.class)
                .getStatusCode().value()).isEqualTo(400);
    }

    // ---- helpers ----

    private Map<String, Object> summary(int months) {
        return owner.get().uri("/api/dashboard/summary?months={months}", months).retrieve().body(Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> thisMonth(Map<String, Object> summary) {
        return ((List<Map<String, Object>>) summary.get("revenueByMonth")).getLast();
    }

    private static long count(Map<String, Object> map, String key) {
        return ((Number) map.get(key)).longValue();
    }

    private LocalDate openDay(int daysAhead) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(daysAhead);
        owner.put().uri("/api/availability/working-hours/{day}", day.getDayOfWeek())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("startTime", "09:00", "endTime", "12:00", "slotDurationMinutes", 60,
                        "maxConcurrentBookings", 10))
                .retrieve().toBodilessEntity();
        return day;
    }

    private String placeOrder(LocalDate day) {
        return (String) guest.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", day + "T09:00:00Z",
                        "lines", List.of(Map.of("itemId", bread, "quantity", 1)),
                        "guestContact", Map.of("name", "Sam", "email", "sam@example.com", "phone", "555-0100")))
                .retrieve().body(Map.class).get("id");
    }

    private void confirm(String orderId) {
        owner.patch().uri("/api/orders/{id}/confirm", orderId).retrieve().toBodilessEntity();
    }

    private Object invoiceFor(String orderId) {
        confirm(orderId);
        return owner.get().uri("/api/orders/{id}/invoice", orderId).retrieve().body(Map.class).get("id");
    }

    private void patch(String uri, Object id, Map<String, Object> body) {
        RestClient.RequestBodySpec request = owner.patch().uri(uri, id);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        assertThat(request.retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
    }
}
