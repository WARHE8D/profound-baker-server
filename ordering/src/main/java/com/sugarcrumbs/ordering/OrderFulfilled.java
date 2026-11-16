package com.sugarcrumbs.ordering;

import java.time.Instant;
import java.util.UUID;

/** The guest collected the order. */
public record OrderFulfilled(UUID orderId, String reference, Instant occurredAt) implements OrderEvent {
}
