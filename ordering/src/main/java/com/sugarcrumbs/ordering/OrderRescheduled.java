package com.sugarcrumbs.ordering;

import java.time.Instant;
import java.util.UUID;

/** The order's pickup moved; not published when it is "moved" to the time it already had. */
public record OrderRescheduled(UUID orderId, String reference, Instant previousPickupAt, Instant pickupAt,
                               Instant occurredAt) implements OrderEvent {
}
