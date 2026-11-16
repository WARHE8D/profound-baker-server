package com.sugarcrumbs.ordering;

import java.time.Instant;
import java.util.UUID;

/** The Owner accepted a pending order. */
public record OrderConfirmed(UUID orderId, String reference, Instant pickupAt, Instant occurredAt)
        implements OrderEvent {
}
