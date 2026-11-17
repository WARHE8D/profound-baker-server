package com.sugarcrumbs.ordering.domain;

/**
 * How a guest paid. Offline only: the bakery takes no card payments, and the Owner records each
 * payment by hand when marking the invoice paid.
 */
public enum PaymentMethod {
    CASH,
    /** Needs the transfer's reference, so the Owner can match it to the bank statement. */
    BANK_TRANSFER
}
