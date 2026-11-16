package com.sugarcrumbs.ordering;

import java.time.Instant;
import java.util.UUID;

/** The Owner turned a pending order down, with a reason meant for the guest. */
public record OrderRejected(UUID orderId, String reference, String reason, Instant occurredAt)
        implements OrderEvent {
}
