package com.sugarcrumbs.ordering.web.dto;

import com.sugarcrumbs.ordering.domain.InvoiceStatus;
import com.sugarcrumbs.ordering.domain.PaymentMethod;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An invoice as the Owner sees it, with its order's reference so a row can be matched to an order
 * at a glance.
 *
 * @param total    {@code subtotal - discountAmount}: what the guest owes
 * @param issuedAt when the order was confirmed and this invoice issued
 */
public record InvoiceResponse(
        UUID id,
        String number,
        UUID orderId,
        String orderReference,
        InvoiceStatus status,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal total,
        @Nullable PaymentMethod paymentMethod,
        @Nullable String paymentReference,
        @Nullable Instant paidAt,
        @Nullable Instant refundedAt,
        Instant issuedAt
) {
}
