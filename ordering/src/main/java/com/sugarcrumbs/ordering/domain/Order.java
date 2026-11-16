package com.sugarcrumbs.ordering.domain;

import com.sugarcrumbs.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A guest's order: one or more {@link OrderLine}s collected at a single pickup
 * slot. Guest checkout only, so the contact details live inline as a
 * {@link GuestContact} rather than pointing at an account.
 *
 * <p>Status transitions are guarded here (see {@link #confirm()} etc.), so an
 * order can never silently jump from {@code CANCELLED} back to
 * {@code CONFIRMED} through a careless update.
 *
 * <p>The JPA entity name is {@code CustomerOrder} because {@code ORDER} is a
 * reserved word in JPQL and SQL; the table is {@code orders}.
 */
@Entity(name = "CustomerOrder")
@Table(schema = "ordering", name = "orders")
public class Order extends BaseEntity {

    private static final Set<OrderStatus> CANCELLABLE_FROM =
            EnumSet.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, OrderStatus.IN_PROGRESS);

    @Column(name = "reference", nullable = false, updatable = false, length = 16)
    private String reference;

    /** Start of the pickup slot. Slot times are local to the bakery; this is the instant. */
    @Column(name = "pickup_at", nullable = false)
    private Instant pickupAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Embedded
    private GuestContact guestContact;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "cancellation_reason", length = 300)
    private String cancellationReason;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {
        // required by JPA
    }

    private Order(Builder builder) {
        this.reference = requireReference(builder.reference);
        this.pickupAt = Objects.requireNonNull(builder.pickupAt, "pickupAt is required");
        this.guestContact = Objects.requireNonNull(builder.guestContact, "guestContact is required");
        this.notes = builder.notes;
        if (builder.lines.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        Set<UUID> seen = new HashSet<>();
        for (LineDraft draft : builder.lines) {
            if (!seen.add(draft.itemId())) {
                throw new IllegalArgumentException("each item may appear only once in an order");
            }
            lines.add(new OrderLine(this, lines.size() + 1, draft.itemId(), draft.itemName(), draft.unitPrice(),
                    draft.quantity()));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Business behavior: explicit state machine, not a raw setter ----

    public void confirm() {
        requireStatus(OrderStatus.PENDING, "confirm");
        this.status = OrderStatus.CONFIRMED;
    }

    public void startPreparation() {
        requireStatus(OrderStatus.CONFIRMED, "start preparation on");
        this.status = OrderStatus.IN_PROGRESS;
    }

    public void fulfill() {
        if (status != OrderStatus.CONFIRMED && status != OrderStatus.IN_PROGRESS) {
            throw new IllegalStateException("cannot fulfill an order in status " + status);
        }
        this.status = OrderStatus.FULFILLED;
    }

    public void cancel(String reason) {
        if (!CANCELLABLE_FROM.contains(status)) {
            throw new IllegalStateException("cannot cancel an order in status " + status);
        }
        this.status = OrderStatus.CANCELLED;
        this.cancellationReason = reason;
    }

    public void reschedule(Instant newPickupAt) {
        if (status == OrderStatus.CANCELLED || status == OrderStatus.FULFILLED) {
            throw new IllegalStateException("cannot reschedule an order in status " + status);
        }
        this.pickupAt = Objects.requireNonNull(newPickupAt, "pickupAt is required");
    }

    public void updateNotes(String newNotes) {
        this.notes = newNotes;
    }

    /** Sum of the line snapshots: what the order costs, whatever the catalog says today. */
    public BigDecimal getSubtotal() {
        return lines.stream().map(OrderLine::getLineTotal)
                .reduce(BigDecimal.ZERO.setScale(OrderLine.AMOUNT_SCALE), BigDecimal::add);
    }

    private void requireStatus(OrderStatus required, String action) {
        if (status != required) {
            throw new IllegalStateException("cannot " + action + " an order in status " + status);
        }
    }

    private static String requireReference(String reference) {
        if (reference == null || !OrderReference.FORMAT.matcher(reference).matches()) {
            throw new IllegalArgumentException("invalid order reference: " + reference);
        }
        return reference;
    }

    // ---- Getters only ----

    public String getReference() {
        return reference;
    }

    public Instant getPickupAt() {
        return pickupAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public GuestContact getGuestContact() {
        return guestContact;
    }

    public String getNotes() {
        return notes;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    @Override
    public String toString() {
        return "Order{id=%s, reference=%s, status=%s, pickupAt=%s, lines=%d}"
                .formatted(getId(), reference, status, pickupAt, lines.size());
    }

    /** What checkout knows about a line before the order exists: the item and its snapshot. */
    public record LineDraft(UUID itemId, String itemName, BigDecimal unitPrice, int quantity) {
    }

    public static final class Builder {
        private String reference;
        private Instant pickupAt;
        private GuestContact guestContact;
        private String notes;
        private final List<LineDraft> lines = new ArrayList<>();

        private Builder() {
        }

        public Builder reference(String reference) {
            this.reference = reference;
            return this;
        }

        public Builder pickupAt(Instant pickupAt) {
            this.pickupAt = pickupAt;
            return this;
        }

        public Builder guestContact(GuestContact guestContact) {
            this.guestContact = guestContact;
            return this;
        }

        public Builder notes(String notes) {
            this.notes = notes;
            return this;
        }

        public Builder line(UUID itemId, String itemName, BigDecimal unitPrice, int quantity) {
            this.lines.add(new LineDraft(itemId, itemName, unitPrice, quantity));
            return this;
        }

        public Order build() {
            return new Order(this);
        }
    }
}
