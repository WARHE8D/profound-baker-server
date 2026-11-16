package com.sugarcrumbs.ordering.domain;

/**
 * Lifecycle states for an {@link Order}. Kept as a plain enum (not a lookup
 * table) since the set of states is fixed and only changes with a code deploy.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    IN_PROGRESS,
    FULFILLED,
    CANCELLED
}
