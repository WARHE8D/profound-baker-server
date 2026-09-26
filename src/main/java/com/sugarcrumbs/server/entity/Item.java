package com.sugarcrumbs.server.entity;

import com.sugarcrumbs.server.entity.common.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A single item on the patissier's menu (e.g. "Chocolate Fraisier",
 * "Custom 3-Tier Wedding Cake").
 *
 * <p><b>Best practices applied in this class:</b>
 * <ul>
 *   <li><b>Rich domain model, not a bag of setters.</b> State changes go
 *       through named business methods ({@link #markAvailable()},
 *       {@link #adjustStock(int)}, {@link #reprice(BigDecimal)} ...) so
 *       invariants — price &gt; 0, stock never negative, name never
 *       blank — can't be bypassed by a stray setter call from a mapper.</li>
 *   <li><b>Money as {@link BigDecimal}</b>, never float/double, with a
 *       fixed scale enforced in code and mirrored at the DB level
 *       ({@code numeric(10,2)}).</li>
 *   <li><b>Lazy, explicit associations.</b> {@link Category} is fetched
 *       lazily so listing items never silently joins categories the
 *       caller didn't ask for.</li>
 *   <li><b>Protected no-args constructor.</b> JPA requires one, but
 *       it's not public, so application code can't create a
 *       half-initialized {@code Item} by accident.</li>
 *   <li><b>Builder for construction</b>, validating required fields
 *       together at creation time instead of via a chain of setters.</li>
 *   <li><b>Defensive copying</b> on the image list — the getter returns
 *       an unmodifiable view so external code can't mutate internal
 *       state through a returned reference.</li>
 *   <li><b>Bean Validation annotations</b> mirror the DB constraints so
 *       invalid DTOs are rejected at the controller layer, before they
 *       ever reach Hibernate.</li>
 *   <li><b>Indexed columns</b> on the fields this app will actually
 *       filter by ({@code category_id}, {@code available}).</li>
 * </ul>
 */
@Entity
@Table(
        name = "items",
        indexes = {
                @Index(name = "idx_items_category_id", columnList = "category_id"),
                @Index(name = "idx_items_available", columnList = "available")
        }
)
public class Item extends BaseEntity {

    private static final int PRICE_SCALE = 2;

    @NotBlank
    @Size(max = 150)
    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Size(max = 2000)
    @Column(name = "description", length = 2000)
    private String description;

    @NotNull
    @DecimalMin(value = "0.01", message = "price must be greater than zero")
    @Digits(integer = 8, fraction = PRICE_SCALE)
    @Column(name = "price", nullable = false, precision = 10, scale = PRICE_SCALE)
    private BigDecimal price;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "category_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_items_category")
    )
    private Category category;

    @Column(name = "available", nullable = false)
    private boolean available = true;

    /**
     * Minimum notice, in hours, the owner needs before this item can be
     * fulfilled (e.g. 72 for a custom wedding cake). 0 means same-day
     * made-to-order is fine.
     */
    @Min(0)
    @Column(name = "lead_time_hours", nullable = false)
    private int leadTimeHours;

    /**
     * Units currently in stock. {@code null} means "unlimited / made to
     * order" — deliberately not a magic number like {@code -1}.
     */
    @Min(0)
    @Column(name = "stock_quantity")
    private Integer stockQuantity;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "item_images",
            joinColumns = @JoinColumn(name = "item_id", foreignKey = @ForeignKey(name = "fk_item_images_item"))
    )
    @OrderColumn(name = "position")
    @Column(name = "image_url", nullable = false)
    private List<String> imageUrls = new ArrayList<>();

    protected Item() {
        // required by JPA/Hibernate; not for application use
    }

    private Item(Builder builder) {
        this.name = requireNonBlank(builder.name, "name");
        this.description = builder.description;
        this.price = normalizePrice(Objects.requireNonNull(builder.price, "price is required"));
        this.category = Objects.requireNonNull(builder.category, "category is required");
        this.available = builder.available;
        this.leadTimeHours = requireNonNegative(builder.leadTimeHours, "leadTimeHours");
        this.stockQuantity = builder.stockQuantity;
        if (builder.imageUrls != null) {
            this.imageUrls = new ArrayList<>(builder.imageUrls);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- Business behavior: state changes go through here, not raw setters ----

    public void rename(String newName) {
        this.name = requireNonBlank(newName, "name");
    }

    public void reprice(BigDecimal newPrice) {
        if (newPrice == null || newPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("price must be greater than zero");
        }
        this.price = normalizePrice(newPrice);
    }

    public void moveToCategory(Category newCategory) {
        this.category = Objects.requireNonNull(newCategory, "category is required");
    }

    public void markAvailable() {
        this.available = true;
    }

    public void markUnavailable() {
        this.available = false;
    }

    public void changeLeadTime(int hours) {
        this.leadTimeHours = requireNonNegative(hours, "leadTimeHours");
    }

    /**
     * Increases or decreases stock by {@code delta}. Pass a negative
     * number to decrement, e.g. when an order is placed.
     *
     * @throws IllegalStateException    if this item has unlimited stock
     *                                   ({@code stockQuantity == null})
     * @throws IllegalArgumentException if the result would go negative
     */
    public void adjustStock(int delta) {
        if (stockQuantity == null) {
            throw new IllegalStateException("item '" + name + "' has unlimited stock; nothing to adjust");
        }
        int updated = stockQuantity + delta;
        if (updated < 0) {
            throw new IllegalArgumentException("insufficient stock for item '" + name + "'");
        }
        this.stockQuantity = updated;
    }

    public void addImage(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("image url cannot be blank");
        }
        this.imageUrls.add(url);
    }

    public void removeImage(String url) {
        this.imageUrls.remove(url);
    }

    /** Whether this item can currently be ordered/booked. */
    public boolean isOrderable() {
        return available && (stockQuantity == null || stockQuantity > 0);
    }

    private static BigDecimal normalizePrice(BigDecimal price) {
        return price.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }

    private static int requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return value;
    }

    public void updateDescription(String newDescription) {
        this.description = newDescription;
    }

    /**
     * Overwrites the stock figure outright (e.g. from a full edit form).
     * Use {@link #adjustStock(int)} instead when the intent is a relative
     * change (an order was placed, a restock happened) — that method
     * enforces "never goes negative"; this one is a direct correction and
     * trusts the caller.
     */
    public void updateStockQuantity(Integer newStockQuantity) {
        if (newStockQuantity != null && newStockQuantity < 0) {
            throw new IllegalArgumentException("stockQuantity cannot be negative");
        }
        this.stockQuantity = newStockQuantity;
    }

    // ---- Getters only; no public setters — see class javadoc ----

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public Category getCategory() {
        return category;
    }

    public boolean isAvailable() {
        return available;
    }

    public int getLeadTimeHours() {
        return leadTimeHours;
    }

    public Integer getStockQuantity() {
        return stockQuantity;
    }

    /** Unmodifiable view — callers can't mutate internal state through this list. */
    public List<String> getImageUrls() {
        return Collections.unmodifiableList(imageUrls);
    }

    @Override
    public String toString() {
        // Deliberately excludes lazy associations (category, imageUrls)
        // to avoid triggering N+1 queries or LazyInitializationException
        // when this is logged outside a transaction.
        return "Item{id=%s, name='%s', price=%s, available=%s}"
                .formatted(getId(), name, price, available);
    }

    public static final class Builder {
        private String name;
        private String description;
        private BigDecimal price;
        private Category category;
        private boolean available = true;
        private int leadTimeHours = 0;
        private Integer stockQuantity;
        private List<String> imageUrls;

        private Builder() {
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder price(BigDecimal price) {
            this.price = price;
            return this;
        }

        public Builder category(Category category) {
            this.category = category;
            return this;
        }

        public Builder available(boolean available) {
            this.available = available;
            return this;
        }

        public Builder leadTimeHours(int leadTimeHours) {
            this.leadTimeHours = leadTimeHours;
            return this;
        }

        public Builder stockQuantity(Integer stockQuantity) {
            this.stockQuantity = stockQuantity;
            return this;
        }

        public Builder imageUrls(List<String> imageUrls) {
            this.imageUrls = imageUrls;
            return this;
        }

        public Item build() {
            return new Item(this);
        }
    }
}
