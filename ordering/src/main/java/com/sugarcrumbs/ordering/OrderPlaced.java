package com.sugarcrumbs.ordering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A guest checked out; the order is pending until the Owner confirms or rejects it. */
public record OrderPlaced(UUID orderId, String reference, Instant pickupAt, BigDecimal subtotal, Instant occurredAt)
        implements OrderEvent {
}
