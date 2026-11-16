package com.sugarcrumbs.ordering;

import java.time.Instant;
import java.util.UUID;

/**
 * An order was called off before pickup. {@code by} says who did it, so a listener can, say,
 * apologise to a guest the Owner cancelled on but not to a guest who cancelled themselves.
 *
 * @param reason may be {@code null}: the Owner isn't required to give one
 */
public record OrderCancelled(UUID orderId, String reference, CancelledBy by, String reason, Instant occurredAt)
        implements OrderEvent {

    public enum CancelledBy {
        /** The Owner, after confirming the order. */
        OWNER,
        /** The guest, through their magic link (#19). */
        GUEST,
        /** {@code StaleOrderCleanupJob}: the Owner never confirmed it within the hold window. */
        SYSTEM
    }
}
