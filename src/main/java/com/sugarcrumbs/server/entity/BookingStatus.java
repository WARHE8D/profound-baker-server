package com.sugarcrumbs.server.entity;

/**
 * Lifecycle states for a {@link Booking}. Kept as a plain enum (not a
 * separate lookup table) since the set of states is fixed, small, and
 * only ever changes with a code deploy — a textbook case for
 * {@code @Enumerated(EnumType.STRING)} rather than a joined table.
 */
public enum BookingStatus {
    PENDING,
    CONFIRMED,
    IN_PROGRESS,
    FULFILLED,
    CANCELLED
}
