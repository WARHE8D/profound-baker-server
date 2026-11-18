package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.ordering.OrderCancelled;
import com.sugarcrumbs.ordering.OrderConfirmed;
import com.sugarcrumbs.ordering.OrderEvent;
import com.sugarcrumbs.ordering.OrderFulfilled;
import com.sugarcrumbs.ordering.OrderPlaced;
import com.sugarcrumbs.ordering.OrderRejected;
import com.sugarcrumbs.ordering.OrderRescheduled;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import com.sugarcrumbs.ordering.persistence.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.EnumMap;
import java.util.Map;

/**
 * The order side of the Business dashboard (#26), counted from the {@link OrderEvent}s
 * {@link OrderService} publishes: after commit, so a rolled-back checkout or confirm is never
 * counted.
 *
 * <ul>
 *   <li>{@code baker_orders_total{status}}: orders entering a status (placed = {@code PENDING}).</li>
 *   <li>{@code baker_stale_orders_cancelled_total}: pending orders the stale-order job cancelled
 *       because the Owner never answered them.</li>
 *   <li>{@code baker_orders_pending}: orders waiting for the Owner right now, read from the
 *       database on each scrape so it is right after a restart too.</li>
 * </ul>
 *
 * Counters are registered up front, so every status reads 0 rather than missing until it first
 * happens, which keeps {@code rate()} and the alert rules simple.
 */
@Component
class OrderMetrics {

    private final Map<OrderStatus, Counter> entered = new EnumMap<>(OrderStatus.class);
    private final Counter staleCancelled;

    OrderMetrics(MeterRegistry registry, OrderRepository orderRepository) {
        for (OrderStatus status : new OrderStatus[] {OrderStatus.PENDING, OrderStatus.CONFIRMED,
                OrderStatus.REJECTED, OrderStatus.CANCELLED, OrderStatus.FULFILLED}) {
            entered.put(status, Counter.builder("baker.orders")
                    .description("Orders entering a status; PENDING counts orders placed")
                    .tag("status", status.name())
                    .register(registry));
        }
        staleCancelled = Counter.builder("baker.stale.orders.cancelled")
                .description("Pending orders auto-cancelled because the Owner never answered them")
                .register(registry);
        Gauge.builder("baker.orders.pending", orderRepository, orders -> orders.countByStatus(OrderStatus.PENDING))
                .description("Orders waiting for the Owner to confirm or reject them")
                .strongReference(true)
                .register(registry);
    }

    @TransactionalEventListener
    void on(OrderEvent event) {
        switch (event) {
            case OrderPlaced ignored -> entered.get(OrderStatus.PENDING).increment();
            case OrderConfirmed ignored -> entered.get(OrderStatus.CONFIRMED).increment();
            case OrderRejected ignored -> entered.get(OrderStatus.REJECTED).increment();
            case OrderFulfilled ignored -> entered.get(OrderStatus.FULFILLED).increment();
            case OrderCancelled cancelled -> {
                entered.get(OrderStatus.CANCELLED).increment();
                if (cancelled.by() == OrderCancelled.CancelledBy.SYSTEM) {
                    staleCancelled.increment();
                }
            }
            case OrderRescheduled ignored -> {
                // Not a status change.
            }
        }
    }
}
