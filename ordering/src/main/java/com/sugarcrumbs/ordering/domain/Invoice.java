package com.sugarcrumbs.ordering.domain;

import com.sugarcrumbs.common.persistence.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The billing record for an {@link Order}.
 *
 * <p><b>Why {@code subtotal} is stored:</b> it is taken from the order's
 * line snapshots when the invoice is issued, so neither a later menu
 * repricing nor an edit to the order changes what an issued invoice says.
 *
 * <p>One invoice per order, enforced by a unique FK: deliberately a
 * {@code @OneToOne}, since this system doesn't split one order across
 * several invoices.
 *
 * <p>Issued when the Owner confirms the order (#20), numbered {@code INV-YYYY-NNNN}.
 */
@Entity
@Table(
        schema = "ordering",
        name = "invoices",
        uniqueConstraints = @UniqueConstraint(name = "uk_invoices_order", columnNames = "order_id"),
        indexes = @Index(name = "idx_invoices_status", columnList = "status")
)
public class Invoice extends BaseEntity {

    private static final int AMOUNT_SCALE = 2;

    /** {@code INV-}, the year of issue, then a sequence number of at least four digits. */
    public static final Pattern NUMBER_FORMAT = Pattern.compile("INV-\\d{4}-\\d{4,}");

    /** What the guest and the Owner quote, e.g. {@code INV-2026-0042}. */
    @Column(name = "number", nullable = false, updatable = false, length = 20)
    private String number;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, foreignKey = @ForeignKey(name = "fk_invoices_order"))
    private Order order;

    /** The order's subtotal (sum of its line snapshots) when this invoice was issued. */
    @NotNull
    @DecimalMin(value = "0.00")
    @Column(name = "subtotal", nullable = false, precision = 10, scale = AMOUNT_SCALE)
    private BigDecimal subtotal;

    @NotNull
    @DecimalMin(value = "0.00")
    @Column(name = "discount_amount", nullable = false, precision = 10, scale = AMOUNT_SCALE)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 20)
    private PaymentMethod paymentMethod;

    /** The bank transfer's reference (required for one), or an optional note for cash. Null until paid. */
    @Size(max = 200)
    @Column(name = "payment_reference", length = 200)
    private String paymentReference;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    protected Invoice() {
        // required by JPA
    }

    private Invoice(Builder builder) {
        this.order = Objects.requireNonNull(builder.order, "order is required");
        if (builder.number == null || !NUMBER_FORMAT.matcher(builder.number).matches()) {
            throw new IllegalArgumentException("invalid invoice number: " + builder.number);
        }
        this.number = builder.number;
        this.subtotal = normalize(Objects.requireNonNull(builder.subtotal, "subtotal is required"));
        if (builder.discountAmount != null) {
            applyDiscount(builder.discountAmount);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Business behavior ----

    /** Total the guest owes: subtotal minus any discount, never negative. */
    public BigDecimal getTotal() {
        BigDecimal total = subtotal.subtract(discountAmount);
        return total.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : total;
    }

    public void applyDiscount(BigDecimal discount) {
        if (status != InvoiceStatus.PENDING) {
            throw new IllegalStateException("cannot change discount on an invoice in status " + status);
        }
        if (discount == null || discount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("discount cannot be negative");
        }
        if (discount.compareTo(subtotal) > 0) {
            throw new IllegalArgumentException("discount cannot exceed subtotal");
        }
        this.discountAmount = normalize(discount);
    }

    /**
     * Records an offline payment. A bank transfer needs its reference, so it can be matched to the
     * statement; for cash a reference is an optional note. {@code paidAt} may lie in the past (the
     * Owner records a payment taken earlier), never in the future.
     */
    public void markPaid(PaymentMethod method, String paymentReference, Instant paidAt) {
        if (status != InvoiceStatus.PENDING) {
            throw new IllegalStateException("cannot mark paid an invoice in status " + status);
        }
        if (method == null) {
            throw new IllegalArgumentException("paymentMethod is required");
        }
        String reference = paymentReference == null || paymentReference.isBlank() ? null : paymentReference.strip();
        if (method == PaymentMethod.BANK_TRANSFER && reference == null) {
            throw new IllegalArgumentException("a bank transfer needs its reference");
        }
        if (paidAt == null || paidAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("paidAt is required and cannot be in the future");
        }
        this.paymentMethod = method;
        this.paymentReference = reference;
        this.paidAt = paidAt;
        this.status = InvoiceStatus.PAID;
    }

    /** The Owner gave the money back. The payment details stay, as the record of what was refunded. */
    public void refund(Instant refundedAt) {
        if (status != InvoiceStatus.PAID) {
            throw new IllegalStateException("only a paid invoice can be refunded (current status: " + status + ")");
        }
        this.refundedAt = Objects.requireNonNull(refundedAt, "refundedAt");
        this.status = InvoiceStatus.REFUNDED;
    }

    public void voidInvoice() {
        if (status != InvoiceStatus.PENDING) {
            throw new IllegalStateException("only a pending invoice can be voided (current status: " + status + ")");
        }
        this.status = InvoiceStatus.VOID;
    }

    private static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    // ---- Getters only ----

    public String getNumber() {
        return number;
    }

    public Order getOrder() {
        return order;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public String getPaymentReference() {
        return paymentReference;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }

    @Override
    public String toString() {
        return "Invoice{id=%s, number=%s, status=%s, total=%s}".formatted(getId(), number, status, getTotal());
    }

    public static final class Builder {
        private Order order;
        private String number;
        private BigDecimal subtotal;
        private BigDecimal discountAmount;

        private Builder() {
        }

        public Builder order(Order order) {
            this.order = order;
            return this;
        }

        public Builder number(String number) {
            this.number = number;
            return this;
        }

        public Builder subtotal(BigDecimal subtotal) {
            this.subtotal = subtotal;
            return this;
        }

        public Builder discountAmount(BigDecimal discountAmount) {
            this.discountAmount = discountAmount;
            return this;
        }

        public Invoice build() {
            return new Invoice(this);
        }
    }
}
