package com.sugarcrumbs.server.entity;

/**
 * Lifecycle states for an {@link Invoice}. {@code VOID} (not
 * {@code CANCELLED}) is used deliberately — standard invoicing/accounting
 * terminology for "cancelled before any payment was collected".
 */
public enum InvoiceStatus {
    PENDING,
    PAID,
    REFUNDED,
    VOID
}
