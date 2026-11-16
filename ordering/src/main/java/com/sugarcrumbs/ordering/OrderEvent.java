package com.sugarcrumbs.ordering;

import java.time.Instant;
import java.util.UUID;

/**
 * Something that happened to an order, published for other modules (and later other services)
 * to react to, e.g. a future notification module emailing the guest. Part of the ordering
 * module's published API: plain records with ids, no entities and no guest contact details, so
 * an event can be stored or sent to a broker as it is. A listener that needs more asks for it.
 *
 * <p>{@code OrderService} publishes each event inside the transaction that made the change.
 * Listen with {@code @ApplicationModuleListener} (or {@code @TransactionalEventListener}): it
 * runs only after that transaction commits, and never for a change that was rolled back. A plain
 * {@code @EventListener} would run before commit and could act on an order that never existed.
 */
public sealed interface OrderEvent
        permits OrderPlaced, OrderConfirmed, OrderRejected, OrderCancelled, OrderRescheduled, OrderFulfilled {

    UUID orderId();

    /** The human-readable reference the guest knows the order by, e.g. {@code SC-7F3K2}. */
    String reference();

    Instant occurredAt();
}
