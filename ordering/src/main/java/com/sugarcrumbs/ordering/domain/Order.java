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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

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

    /** How long a guest's magic link keeps working once the order is over (fulfilled, rejected or cancelled). */
    public static final Duration ACCESS_AFTER_FINAL = Duration.ofDays(30);

    private static final Pattern TOKEN_HASH = Pattern.compile("[0-9a-f]{64}");

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

    @Column(name = "rejection_reason", length = 300)
    private String rejectionReason;

    /** Hex SHA-256 of the guest's magic-link token; the token itself is never stored. */
    @Column(name = "access_token_hash", length = 64)
    private String accessTokenHash;

    /** {@code null} while the order is open; set when it reaches a final state. */
    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

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

    // ---- Guest access ----

    /**
     * Records the hash of the guest's magic-link token. Once only: a second link would leave the
     * guest holding one that no longer works, with no way to be told.
     */
    public void issueAccessToken(String tokenHash) {
        if (accessTokenHash != null) {
            throw new IllegalStateException("this order already has an access token");
        }
        if (tokenHash == null || !TOKEN_HASH.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException("access token hash must be 64 lower-case hex characters");
        }
        this.accessTokenHash = tokenHash;
    }

    /** Whether the guest's link still opens this order at {@code now}. Orders from before links never do. */
    public boolean isAccessTokenValid(Instant now) {
        return accessTokenHash != null && (accessTokenExpiresAt == null || now.isBefore(accessTokenExpiresAt));
    }

    /** Called on every move into a final state: the guest keeps their link for a while to see how it ended. */
    private void startAccessExpiry() {
        if (accessTokenHash != null) {
            this.accessTokenExpiresAt = Instant.now().plus(ACCESS_AFTER_FINAL);
        }
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
        startAccessExpiry();
    }

    public void cancel(String reason) {
        if (!CANCELLABLE_FROM.contains(status)) {
            throw new IllegalStateException("cannot cancel an order in status " + status);
        }
        this.status = OrderStatus.CANCELLED;
        this.cancellationReason = reason;
        startAccessExpiry();
    }

    /**
     * The Owner turns down a pending order. Only a pending one: once confirmed, calling it
     * off is a cancellation, which reports count differently. A guest is owed a reason.
     */
    public void reject(String reason) {
        requireStatus(OrderStatus.PENDING, "reject");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a rejection needs a reason");
        }
        this.status = OrderStatus.REJECTED;
        this.rejectionReason = reason.strip();
        startAccessExpiry();
    }

    public void reschedule(Instant newPickupAt) {
        if (status == OrderStatus.FULFILLED || OrderStatus.WITHDRAWN.contains(status)) {
            throw new IllegalStateException("cannot reschedule an order in status " + status);
        }
        this.pickupAt = Objects.requireNonNull(newPickupAt, "pickupAt is required");
    }

    /** Only while pending: once confirmed, the Owner has agreed to the order as it stood. */
    public void updateNotes(String newNotes) {
        requireStatus(OrderStatus.PENDING, "edit the notes of");
        this.notes = newNotes;
    }

    /**
     * Replaces the order's contents with {@code drafts}, the full list of lines wanted, while it
     * is still pending. Lines are matched by item: an item already on the order keeps its line,
     * its line number and the name and price the guest saw (only its quantity changes); an item
     * not on it gets a new line at the end with the draft's snapshot; an item left out loses its
     * line, including a line whose item has since been deleted from the catalog.
     *
     * <p>New lines are numbered after every existing one, removed ones included, so a number is
     * never reused within one flush (Hibernate inserts before it deletes, and line numbers are
     * unique per order). Numbers may therefore have gaps; they only fix the display order.
     */
    public void editLines(List<LineDraft> drafts) {
        requireStatus(OrderStatus.PENDING, "edit the lines of");
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        Map<UUID, LineDraft> wanted = new LinkedHashMap<>();
        for (LineDraft draft : drafts) {
            if (wanted.putIfAbsent(Objects.requireNonNull(draft.itemId(), "itemId"), draft) != null) {
                throw new IllegalArgumentException("each item may appear only once in an order");
            }
            // Checked up front so a bad draft leaves the order untouched rather than half edited.
            if (draft.quantity() <= 0) {
                throw new IllegalArgumentException("quantity must be positive");
            }
        }
        int nextLineNo = lines.stream().mapToInt(OrderLine::getLineNo).max().orElse(0) + 1;
        Map<UUID, OrderLine> current = new HashMap<>();
        for (OrderLine line : lines) {
            if (line.getItemId() != null) {
                current.put(line.getItemId(), line);
            }
        }
        lines.removeIf(line -> line.getItemId() == null || !wanted.containsKey(line.getItemId()));
        for (LineDraft draft : wanted.values()) {
            OrderLine existing = current.get(draft.itemId());
            if (existing != null) {
                existing.changeQuantity(draft.quantity());
            } else {
                lines.add(new OrderLine(this, nextLineNo++, draft.itemId(), draft.itemName(), draft.unitPrice(),
                        draft.quantity()));
            }
        }
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

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getAccessTokenHash() {
        return accessTokenHash;
    }

    public Instant getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
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
