package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The overbooking race (#15): counting a slot's orders and then inserting one must not let two
 * requests both take the last place. Each test fires several requests at a capacity-1 slot at
 * the same moment and checks that exactly one wins, both in the responses and in the database.
 *
 * <p>Capacity lives on the weekday's {@code WorkingHours} row, shared with other test classes,
 * so each test sets it itself and uses its own pickup date (counts are per slot on a date, so
 * other classes' orders on the same weekday don't interfere).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutConcurrencyTests extends AbstractIntegrationTest {

    /** Below Hikari's default pool of 10: every request holds a connection while it waits for the lock. */
    private static final int GUESTS = 8;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    PasswordEncoder passwordEncoder;

    private RestClient shopper;
    private RestClient owner;
    private String itemId;

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
        String categoryId = (String) owner.post().uri("/api/catalogue/categories")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("name", "Race " + System.nanoTime()))
                .retrieve().body(Map.class).get("id");
        // Untracked stock and no lead time, so the slot is the only thing the requests compete for.
        itemId = (String) owner.post().uri("/api/catalogue/items")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new HashMap<>(Map.of("name", "Race loaf", "price", "4.00", "categoryId", categoryId,
                        "leadTimeHours", 0)))
                .retrieve().body(Map.class).get("id");
    }

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void parallelCheckoutsIntoTheLastPlaceLetExactlyOneThrough() throws Exception {
        LocalDate day = openWithCapacityOne(63);
        Instant slot = day.atTime(9, 0).toInstant(ZoneOffset.UTC);

        List<Integer> statuses = race(GUESTS, i -> () -> checkout(slot).getStatusCode().value());

        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(liveOrdersAt(slot)).isOne();
    }

    /** Reschedule takes the same lock, so moving an order into a slot can't overbook it either. */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRescheduleRacingCheckoutsForTheLastPlaceCannotOverbookIt() throws Exception {
        LocalDate day = openWithCapacityOne(64);
        Instant contested = day.atTime(9, 0).toInstant(ZoneOffset.UTC);
        Instant elsewhere = day.atTime(10, 0).toInstant(ZoneOffset.UTC);
        ResponseEntity<Map> existing = checkout(elsewhere);
        assertThat(existing.getStatusCode().value()).isEqualTo(201);
        Object existingId = existing.getBody().get("id");

        // Request 0 moves the existing order into the contested slot; the rest are new guests.
        List<Integer> statuses = race(GUESTS, i -> () -> i == 0
                ? owner.put().uri("/api/orders/{id}/schedule", existingId)
                        .contentType(MediaType.APPLICATION_JSON).body(Map.of("pickupAt", contested.toString()))
                        .retrieve().toBodilessEntity().getStatusCode().value()
                : checkout(contested).getStatusCode().value());

        assertThat(statuses).isSubsetOf(200, 201, 409);
        assertThat(statuses).filteredOn(s -> s != 409).hasSize(1);
        assertThat(liveOrdersAt(contested)).isOne();
    }

    /**
     * A transaction that holds the day's lock and never lets go must cost a waiting guest a
     * 409 after the lock timeout, not a request that hangs (and a connection held) forever.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStuckLockTimesOutAsA409() throws Exception {
        LocalDate day = openWithCapacityOne(65);
        Instant slot = day.atTime(9, 0).toInstant(ZoneOffset.UTC);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM ordering.working_hours WHERE day_of_week = ? FOR UPDATE")) {
                lock.setString(1, day.getDayOfWeek().name());
                lock.executeQuery().close();
            }
            try {
                ResponseEntity<Map> blocked = checkout(slot);

                assertThat(blocked.getStatusCode().value()).isEqualTo(409);
            } finally {
                holder.rollback();
            }
        }
        assertThat(liveOrdersAt(slot)).isZero();
        assertThat(checkout(slot).getStatusCode().value()).isEqualTo(201);
    }

    // ---- helpers ----

    /**
     * Starts {@code count} requests together behind a start gate, so they really overlap
     * instead of running one after another as their threads happen to start.
     */
    private List<Integer> race(int count, IntFunction<Callable<Integer>> request)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<Integer> call = request.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Opens the weekday of {@code today + daysAhead} 09:00–11:00 in one-hour slots holding a single order each. */
    private LocalDate openWithCapacityOne(int daysAhead) {
        LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(daysAhead);
        ResponseEntity<Void> response = owner.put().uri("/api/availability/working-hours/{day}", day.getDayOfWeek())
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("startTime", "09:00", "endTime", "11:00", "slotDurationMinutes", 60,
                        "maxConcurrentBookings", 1))
                .retrieve().toBodilessEntity();
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return day;
    }

    private ResponseEntity<Map> checkout(Instant pickupAt) {
        return shopper.post().uri("/api/public/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "pickupAt", pickupAt.toString(),
                        "lines", List.of(Map.of("itemId", itemId, "quantity", 1)),
                        "guestContact", Map.of("name", "Racer", "email", "racer@example.com", "phone", "555-0100")))
                .retrieve().toEntity(Map.class);
    }

    private int liveOrdersAt(Instant pickupAt) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ordering.orders WHERE pickup_at = ? AND status <> 'CANCELLED'",
                Integer.class, Timestamp.from(pickupAt));
    }
}
