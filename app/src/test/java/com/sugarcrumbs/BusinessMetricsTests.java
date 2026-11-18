package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import com.sugarcrumbs.ordering.application.StaleOrderCleanupJob;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The baker_* metrics behind the Business dashboard and the alert rules (#26), driven through the
 * real API. The meter registry is shared with every other test in the context, so each test compares
 * before and after rather than expecting absolute values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BusinessMetricsTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    MeterRegistry registry;

    @Autowired
    StaleOrderCleanupJob staleOrderCleanupJob;

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
                .body(Map.of("name", "Metrics " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
        bread = (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HashMap<>(Map.of("name", "Counted loaf", "price", "7.50", "categoryId", categoryId,
                        "leadTimeHours", 0)))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    void anOrderIsCountedIntoEachStatusItEnters() {
        double placed = orders("PENDING");
        double confirmed = orders("CONFIRMED");
        double fulfilled = orders("FULFILLED");
        double rejected = orders("REJECTED");
        LocalDate day = openDay(140);

        Map<String, Object> kept = placeOrder(day, 1);
        Map<String, Object> turnedDown = placeOrder(day, 1);
        assertThat(orders("PENDING")).isEqualTo(placed + 2);
        assertThat(pending()).isGreaterThanOrEqualTo(2);

        assertThat(status(owner.patch().uri("/api/orders/{id}/confirm", kept.get("id")))).isEqualTo(200);
        assertThat(status(owner.patch().uri("/api/orders/{id}/start-preparation", kept.get("id")))).isEqualTo(200);
        assertThat(status(owner.patch().uri("/api/orders/{id}/fulfill", kept.get("id")))).isEqualTo(200);
        assertThat(status(owner.patch().uri("/api/orders/{id}/reject", turnedDown.get("id"))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "Oven is broken")))).isEqualTo(200);

        assertThat(orders("CONFIRMED")).isEqualTo(confirmed + 1);
        assertThat(orders("FULFILLED")).isEqualTo(fulfilled + 1);
        assertThat(orders("REJECTED")).isEqualTo(rejected + 1);
    }

    /** A checkout refused (here, a past pickup) never reaches the counters: they count committed changes. */
    @Test
    void aRefusedCheckoutIsNotCounted() {
        double placed = orders("PENDING");

        Map<String, Object> refused = guest.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(orderBody(LocalDate.now(ZoneOffset.UTC).minusDays(1), 1))
                .retrieve().body(Map.class);

        assertThat(refused).containsEntry("status", 400);
        assertThat(orders("PENDING")).isEqualTo(placed);
    }

    @Test
    void anOrderTheOwnerNeverAnsweredIsCountedAsStaleWhenAutoCancelled() {
        double stale = registry.get("baker.stale.orders.cancelled").counter().count();
        double cancelled = orders("CANCELLED");
        Map<String, Object> order = placeOrder(openDay(141), 1);
        jdbc.update("UPDATE ordering.orders SET created_at = now() - interval '3 days' WHERE id = ?",
                UUID.fromString((String) order.get("id")));

        staleOrderCleanupJob.cancelStaleOrders();

        assertThat(registry.get("baker.stale.orders.cancelled").counter().count()).isGreaterThanOrEqualTo(stale + 1);
        assertThat(orders("CANCELLED")).isGreaterThanOrEqualTo(cancelled + 1);
    }

    @Test
    void paidAndRefundedInvoiceTotalsAreCounted() {
        double paid = amount("baker.invoices.paid.amount");
        double refunded = amount("baker.invoices.refunded.amount");
        Map<String, Object> order = placeOrder(openDay(142), 2);
        owner.patch().uri("/api/orders/{id}/confirm", order.get("id")).retrieve().toBodilessEntity();
        Object invoiceId = owner.get().uri("/api/orders/{id}/invoice", order.get("id")).retrieve().body(Map.class)
                .get("id");

        assertThat(status(owner.patch().uri("/api/invoices/{id}/discount", invoiceId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", "1.00")))).isEqualTo(200);
        assertThat(status(owner.patch().uri("/api/invoices/{id}/pay", invoiceId)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("method", "CASH")))).isEqualTo(200);
        assertThat(amount("baker.invoices.paid.amount")).isEqualTo(paid + 14.00);

        assertThat(status(owner.patch().uri("/api/invoices/{id}/refund", invoiceId))).isEqualTo(200);
        assertThat(amount("baker.invoices.refunded.amount")).isEqualTo(refunded + 14.00);
    }

    @Test
    void failedOwnerLoginsAreCountedButSuccessfulOnesAreNot() {
        double failures = registry.get("baker.auth.login.failures").counter().count();

        OwnerFixture.login(guest);
        for (Map<String, String> wrong : List.of(Map.of("email", OwnerFixture.EMAIL, "password", "not-the-password"),
                Map.of("email", "stranger@example.com", "password", OwnerFixture.PASSWORD))) {
            assertThat(status(guest.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).body(wrong)))
                    .isEqualTo(401);
        }

        assertThat(registry.get("baker.auth.login.failures").counter().count()).isEqualTo(failures + 2);
    }

    private double orders(String status) {
        return registry.get("baker.orders").tag("status", status).counter().count();
    }

    private double pending() {
        return registry.get("baker.orders.pending").gauge().value();
    }

    private double amount(String name) {
        return registry.get(name).counter().count();
    }

    private static int status(RestClient.RequestHeadersSpec<?> request) {
        return request.retrieve().toBodilessEntity().getStatusCode().value();
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

    private Map<String, Object> placeOrder(LocalDate day, int quantity) {
        Map<String, Object> order = guest.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(orderBody(day, quantity))
                .retrieve().body(Map.class);
        assertThat(order).containsKey("id");
        return order;
    }

    private Map<String, Object> orderBody(LocalDate day, int quantity) {
        return Map.of(
                "pickupAt", day + "T09:00:00Z",
                "lines", List.of(Map.of("itemId", bread, "quantity", quantity)),
                "guestContact", Map.of("name", "Mira", "email", "mira@example.com", "phone", "555-0101"));
    }
}
