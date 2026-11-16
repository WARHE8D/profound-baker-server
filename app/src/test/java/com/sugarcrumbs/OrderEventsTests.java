package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import com.sugarcrumbs.ordering.OrderCancelled;
import com.sugarcrumbs.ordering.OrderConfirmed;
import com.sugarcrumbs.ordering.OrderEvent;
import com.sugarcrumbs.ordering.OrderPlaced;
import com.sugarcrumbs.ordering.OrderRescheduled;
import com.sugarcrumbs.ordering.application.OrderService;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain events (#17) as a listener sees them: a {@code @TransactionalEventListener} (the
 * after-commit kind every future listener must be) gets each committed change, in order, and
 * nothing at all for a change whose transaction rolled back.
 *
 * <p>Reaches into {@code ordering}'s internals ({@code OrderService}) only to run a transition in a
 * transaction it can roll back; production code never does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderEventsTests extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    CommittedEvents committed;

    @Autowired
    OrderService orderService;

    @Autowired
    TransactionTemplate transactions;

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
                .body(Map.of("name", "Events " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    void aCommittedLifecycleReachesTheListenerInOrder() {
        String bread = createItem("Event loaf", 10);
        LocalDate day = openDay(80);
        Map<String, Object> order = checkout(day, bread, 2).getBody();
        UUID orderId = UUID.fromString((String) order.get("id"));

        owner.patch().uri("/api/orders/{id}/confirm", orderId).retrieve().toBodilessEntity();
        owner.put().uri("/api/orders/{id}/schedule", orderId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("pickupAt", day + "T10:00:00Z")).retrieve().toBodilessEntity();
        owner.patch().uri("/api/orders/{id}/cancel", orderId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("reason", "flooded")).retrieve().toBodilessEntity();

        List<OrderEvent> events = committed.of(orderId);
        assertThat(events).extracting(Object::getClass).containsExactly(
                OrderPlaced.class, OrderConfirmed.class, OrderRescheduled.class, OrderCancelled.class);
        assertThat(events).allSatisfy(event -> assertThat(event.reference()).isEqualTo(order.get("reference")));
        assertThat(((OrderPlaced) events.getFirst()).subtotal()).isEqualByComparingTo("8.00");
        assertThat(((OrderCancelled) events.getLast()).by()).isEqualTo(OrderCancelled.CancelledBy.OWNER);
    }

    /**
     * The service publishes inside its transaction, so a rollback after the event was raised
     * must keep it from every listener, and leave the order as it was.
     */
    @Test
    void aRolledBackTransitionReachesNoListener() {
        String bread = createItem("Rollback loaf", 10);
        UUID orderId = UUID.fromString((String) checkout(openDay(81), bread, 1).getBody().get("id"));

        transactions.executeWithoutResult(status -> {
            orderService.confirm(orderId);
            status.setRollbackOnly();
        });

        assertThat(committed.of(orderId)).extracting(Object::getClass).containsExactly(OrderPlaced.class);
        assertThat(orderService.getOrThrow(orderId).getStatus()).isEqualTo(OrderStatus.PENDING);
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

    private ResponseEntity<Map> checkout(LocalDate day, String itemId, int quantity) {
        return shopper.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", day + "T09:00:00Z",
                        "lines", List.of(Map.of("itemId", itemId, "quantity", quantity)),
                        "guestContact", Map.of("name", "Eve", "email", "eve@example.com", "phone", "555-0100")))
                .retrieve().toEntity(Map.class);
    }

    private String createItem(String name, int stock) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "name", name, "price", "4.00", "categoryId", categoryId, "leadTimeHours", 0,
                "stockQuantity", stock));
        return (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(Map.class).get("id");
    }

    /** Records what an after-commit listener receives; the default phase is AFTER_COMMIT. */
    static class CommittedEvents {

        private final List<OrderEvent> events = new CopyOnWriteArrayList<>();

        @TransactionalEventListener
        void on(OrderEvent event) {
            events.add(event);
        }

        List<OrderEvent> all() {
            return List.copyOf(events);
        }

        List<OrderEvent> of(UUID orderId) {
            return events.stream().filter(event -> event.orderId().equals(orderId)).toList();
        }
    }

    @TestConfiguration
    static class ListenerConfig {

        @Bean
        CommittedEvents committedEvents() {
            return new CommittedEvents();
        }
    }
}
