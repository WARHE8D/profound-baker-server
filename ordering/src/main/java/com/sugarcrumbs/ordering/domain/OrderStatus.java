package com.sugarcrumbs.ordering.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle states for an {@link Order}. Kept as a plain enum (not a lookup
 * table) since the set of states is fixed and only changes with a code deploy.
 *
 * <p>{@code REJECTED} and {@code CANCELLED} both end an order before pickup, but
 * are kept apart so reports can tell the Owner turning an order down from a guest
 * or the system calling it off.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    IN_PROGRESS,
    FULFILLED,
    /** The Owner turned a pending order down. */
    REJECTED,
    /** Called off by the guest, the Owner (after confirming) or the stale-order job. */
    CANCELLED;

    /** Orders that ended without a pickup: they no longer take a place in their slot, nor stock. */
    public static final Set<OrderStatus> WITHDRAWN = EnumSet.of(REJECTED, CANCELLED);
}
