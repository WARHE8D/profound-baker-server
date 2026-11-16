package com.sugarcrumbs.ordering.domain;

import com.sugarcrumbs.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One item on an {@link Order}, with the item's name and unit price copied in at
 * checkout. Repricing or renaming the item in the catalog later never changes
 * what this order says or costs, like a printed receipt.
 *
 * <p>{@code itemId} is a plain UUID (the item belongs to the catalog module) and
 * may outlive the item: the snapshot is what the line means, the id is only a
 * link back while the item exists. Lines are created only through their order.
 */
@Entity
@Table(schema = "ordering", name = "order_lines")
public class OrderLine extends BaseEntity {

    static final int AMOUNT_SCALE = 2;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    /** 1-based position, so lines always come back in the order the guest listed them. */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "item_name", nullable = false, length = 150)
    private String itemName;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = AMOUNT_SCALE)
    private BigDecimal unitPrice;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** unitPrice × quantity, stored so totals and reports never depend on recomputing. */
    @Column(name = "line_total", nullable = false, precision = 10, scale = AMOUNT_SCALE)
    private BigDecimal lineTotal;

    protected OrderLine() {
        // required by JPA
    }

    OrderLine(Order order, int lineNo, UUID itemId, String itemName, BigDecimal unitPrice, int quantity) {
        this.order = Objects.requireNonNull(order, "order");
        this.lineNo = lineNo;
        this.itemId = Objects.requireNonNull(itemId, "itemId");
        if (itemName == null || itemName.isBlank()) {
            throw new IllegalArgumentException("itemName cannot be blank");
        }
        this.itemName = itemName;
        if (unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice cannot be negative");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.unitPrice = unitPrice.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        this.quantity = quantity;
        this.lineTotal = this.unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    // ---- Getters only ----

    public Order getOrder() {
        return order;
    }

    public int getLineNo() {
        return lineNo;
    }

    public UUID getItemId() {
        return itemId;
    }

    public String getItemName() {
        return itemName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }

    @Override
    public String toString() {
        return "OrderLine{lineNo=%d, itemId=%s, quantity=%d, lineTotal=%s}"
                .formatted(lineNo, itemId, quantity, lineTotal);
    }
}
