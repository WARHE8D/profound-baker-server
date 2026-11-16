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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Owner's invoice API (#21): finding invoices, discounting, recording an offline payment and
 * refunding it. Each test confirms its own orders, so it only ever looks at invoices it issued.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoiceApiTests extends AbstractIntegrationTest {

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
                .body(Map.of("name", "Invoice API " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
        bread = (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HashMap<>(Map.of("name", "Billed loaf", "price", "10.00", "categoryId", categoryId,
                        "leadTimeHours", 0)))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    void anOrdersInvoiceIsFoundByTheOrderOnceConfirmed() {
        Map<String, Object> order = placeOrder(openDay(110), 2);

        assertThat(owner.get().uri("/api/orders/{id}/invoice", order.get("id")).retrieve().toEntity(Map.class)
                .getStatusCode().value()).as("not invoiced while pending").isEqualTo(404);

        Map<String, Object> invoice = confirmAndGetInvoice(order);

        assertThat(invoice)
                .containsEntry("orderId", order.get("id"))
                .containsEntry("orderReference", order.get("reference"))
                .containsEntry("status", "PENDING")
                .containsEntry("subtotal", 20.0)
                .containsEntry("total", 20.0)
                .containsKeys("number", "issuedAt");
        assertThat(owner.get().uri("/api/invoices/{id}", invoice.get("id")).retrieve().body(Map.class))
                .containsEntry("number", invoice.get("number"));
    }

    @Test
    void aDiscountThenABankTransferThenARefund() {
        Map<String, Object> invoice = confirmAndGetInvoice(placeOrder(openDay(111), 3));
        Object id = invoice.get("id");

        Map<String, Object> discounted = patch("/api/invoices/{id}/discount", id, Map.of("amount", "4.50")).getBody();
        assertThat(discounted).containsEntry("discountAmount", 4.5).containsEntry("total", 25.5);
        assertThat(patch("/api/invoices/{id}/discount", id, Map.of("amount", "30.01")).getStatusCode().value())
                .as("more than the subtotal").isEqualTo(400);

        ResponseEntity<Map> noReference = patch("/api/invoices/{id}/pay", id, Map.of("method", "BANK_TRANSFER"));
        assertThat(noReference.getStatusCode().value()).isEqualTo(400);
        assertThat((String) noReference.getBody().get("message")).contains("reference");

        Instant yesterday = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        Map<String, Object> paid = patch("/api/invoices/{id}/pay", id, Map.of(
                "method", "BANK_TRANSFER", "reference", "TRX-4471", "paidAt", yesterday.toString())).getBody();
        assertThat(paid)
                .containsEntry("status", "PAID")
                .containsEntry("paymentMethod", "BANK_TRANSFER")
                .containsEntry("paymentReference", "TRX-4471")
                .containsEntry("paidAt", yesterday.toString());
        assertThat(patch("/api/invoices/{id}/discount", id, Map.of("amount", "1.00")).getStatusCode().value())
                .as("no discount once paid").isEqualTo(400);

        Map<String, Object> refunded = patch("/api/invoices/{id}/refund", id, null).getBody();
        assertThat(refunded).containsEntry("status", "REFUNDED").containsEntry("paymentReference", "TRX-4471");
        assertThat(refunded.get("refundedAt")).isNotNull();
    }

    @Test
    void cashNeedsNoReferenceButAPaymentCannotBeDatedInTheFutureOrUseAnOldMethod() {
        Object id = confirmAndGetInvoice(placeOrder(openDay(112), 1)).get("id");

        assertThat(patch("/api/invoices/{id}/pay", id, Map.of("method", "CASH",
                "paidAt", Instant.now().plus(1, ChronoUnit.DAYS).toString())).getStatusCode().value()).isEqualTo(400);
        assertThat(patch("/api/invoices/{id}/pay", id, Map.of("method", "ONLINE_CARD")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(patch("/api/invoices/{id}/refund", id, null).getStatusCode().value())
                .as("nothing paid yet").isEqualTo(400);

        Map<String, Object> paid = patch("/api/invoices/{id}/pay", id, Map.of("method", "CASH")).getBody();

        assertThat(paid).containsEntry("status", "PAID").containsEntry("paymentMethod", "CASH");
        assertThat(paid.get("paymentReference")).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListFiltersByStatusSearchAndIssueRange() {
        LocalDate day = openDay(113);
        Map<String, Object> unpaid = confirmAndGetInvoice(placeOrder(day, 1));
        Map<String, Object> paid = confirmAndGetInvoice(placeOrder(day, 1));
        patch("/api/invoices/{id}/pay", paid.get("id"), Map.of("method", "CASH"));
        String unpaidNumber = (String) unpaid.get("number");
        String paidReference = (String) paid.get("orderReference");

        assertThat(numbers("/api/invoices?q={q}", unpaidNumber.toLowerCase())).containsExactly(unpaidNumber);
        assertThat(numbers("/api/invoices?q={q}", paidReference)).containsExactly((String) paid.get("number"));
        assertThat(numbers("/api/invoices?q={q}&status=PAID", unpaidNumber)).isEmpty();
        assertThat(numbers("/api/invoices?q={q}&from={from}", unpaidNumber,
                Instant.now().plus(1, ChronoUnit.HOURS).toString())).isEmpty();
        assertThat(numbers("/api/invoices?status=PENDING&status=PAID&size=100")).contains(unpaidNumber,
                (String) paid.get("number"));
        assertThat(owner.get().uri("/api/invoices?q={q}", "x".repeat(51)).retrieve().toEntity(Map.class)
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

    private Map<String, Object> placeOrder(LocalDate day, int quantity) {
        return guest.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", day + "T09:00:00Z",
                        "lines", List.of(Map.of("itemId", bread, "quantity", quantity)),
                        "guestContact", Map.of("name", "Ivo", "email", "ivo@example.com", "phone", "555-0100")))
                .retrieve().body(Map.class);
    }

    private Map<String, Object> confirmAndGetInvoice(Map<String, Object> order) {
        owner.patch().uri("/api/orders/{id}/confirm", order.get("id")).retrieve().toBodilessEntity();
        ResponseEntity<Map> invoice = owner.get().uri("/api/orders/{id}/invoice", order.get("id"))
                .retrieve().toEntity(Map.class);
        assertThat(invoice.getStatusCode().value()).isEqualTo(200);
        return invoice.getBody();
    }

    private ResponseEntity<Map> patch(String uri, Object id, Map<String, Object> body) {
        RestClient.RequestBodySpec request = owner.patch().uri(uri, id);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return request.retrieve().toEntity(Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> numbers(String uri, Object... variables) {
        ResponseEntity<Map> page = owner.get().uri(uri, variables).retrieve().toEntity(Map.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        return ((List<Map<String, Object>>) page.getBody().get("content")).stream()
                .map(invoice -> (String) invoice.get("number")).toList();
    }
}
