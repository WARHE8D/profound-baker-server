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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invoice lifecycle (#20), driven through the order API: confirming issues a numbered invoice
 * for the order's subtotal, cancelling voids it unless it was paid, and a rejected order is never
 * invoiced. There is no invoice API yet (#21), so the invoice is read from the database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoiceLifecycleTests extends AbstractIntegrationTest {

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
                .body(Map.of("name", "Invoices " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
        bread = (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HashMap<>(Map.of("name", "Invoice loaf", "price", "6.25", "categoryId", categoryId,
                        "leadTimeHours", 0)))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    void confirmingIssuesANumberedInvoiceForTheOrdersSubtotal() {
        LocalDate day = openDay(100);
        Object first = placeOrder(day, 3);
        Object second = placeOrder(day, 1);
        assertThat(invoiceOf(first)).as("nothing billed while pending").isNull();

        confirm(first);
        confirm(second);

        Map<String, Object> invoice = invoiceOf(first);
        assertThat((String) invoice.get("number")).matches("INV-\\d{4}-\\d{4,}");
        assertThat((BigDecimal) invoice.get("subtotal")).isEqualByComparingTo("18.75");
        assertThat(invoice).containsEntry("status", "PENDING");
        assertThat(sequenceOf(invoiceOf(second))).isGreaterThan(sequenceOf(invoice));
    }

    @Test
    void cancellingAConfirmedOrderVoidsItsUnpaidInvoice() {
        Object orderId = placeOrder(openDay(101), 1);
        confirm(orderId);

        owner.patch().uri("/api/orders/{id}/cancel", orderId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "flooded"))
                .retrieve().toBodilessEntity();

        assertThat(invoiceOf(orderId)).containsEntry("status", "VOID");
    }

    /** Money changed hands, so giving it back is the Owner's decision, not a side effect of cancelling. */
    @Test
    void aPaidInvoiceSurvivesItsOrdersCancellation() {
        Object orderId = placeOrder(openDay(102), 1);
        confirm(orderId);
        jdbc.update("""
                UPDATE ordering.invoices SET status = 'PAID', payment_method = 'CASH', paid_at = now()
                WHERE order_id = ?::uuid
                """, orderId);

        owner.patch().uri("/api/orders/{id}/cancel", orderId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "flooded"))
                .retrieve().toBodilessEntity();

        assertThat(invoiceOf(orderId)).containsEntry("status", "PAID");
    }

    @Test
    void aRejectedOrderIsNeverInvoiced() {
        Object orderId = placeOrder(openDay(103), 1);

        owner.patch().uri("/api/orders/{id}/reject", orderId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "fully booked"))
                .retrieve().toBodilessEntity();

        assertThat(invoiceOf(orderId)).isNull();
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

    private Object placeOrder(LocalDate day, int quantity) {
        Map<String, Object> placed = guest.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", day + "T09:00:00Z",
                        "lines", List.of(Map.of("itemId", bread, "quantity", quantity)),
                        "guestContact", Map.of("name", "Ivy", "email", "ivy@example.com", "phone", "555-0100")))
                .retrieve().body(Map.class);
        return placed.get("id");
    }

    private void confirm(Object orderId) {
        assertThat(owner.patch().uri("/api/orders/{id}/confirm", orderId).retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(200);
    }

    private Map<String, Object> invoiceOf(Object orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT number, subtotal, status FROM ordering.invoices WHERE order_id = ?::uuid", orderId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static long sequenceOf(Map<String, Object> invoice) {
        String number = (String) invoice.get("number");
        return Long.parseLong(number.substring(number.lastIndexOf('-') + 1));
    }
}
