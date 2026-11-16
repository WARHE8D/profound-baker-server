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

    /** External reference, e.g. a Stripe PaymentIntent/charge id. Null until paid. */
    @Size(max = 200)
    @Column(name = "payment_reference", length = 200)
    private String paymentReference;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected Invoice() {
        // required by JPA
    }

    private Invoice(Builder builder) {
        this.order = Objects.requireNonNull(builder.order, "order is required");
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

    public void markPaid(PaymentMethod method, String paymentReference, Instant paidAt) {
        if (status != InvoiceStatus.PENDING) {
            throw new IllegalStateException("cannot mark paid an invoice in status " + status);
        }
        this.paymentMethod = Objects.requireNonNull(method, "paymentMethod is required");
        this.paymentReference = paymentReference;
        this.paidAt = Objects.requireNonNull(paidAt, "paidAt is required");
        this.status = InvoiceStatus.PAID;
    }

    public void refund() {
        if (status != InvoiceStatus.PAID) {
            throw new IllegalStateException("only a paid invoice can be refunded (current status: " + status + ")");
        }
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

    @Override
    public String toString() {
        return "Invoice{id=%s, status=%s, total=%s}".formatted(getId(), status, getTotal());
    }

    public static final class Builder {
        private Order order;
        private BigDecimal subtotal;
        private BigDecimal discountAmount;

        private Builder() {
        }

        public Builder order(Order order) {
            this.order = order;
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
