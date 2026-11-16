package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.catalog.CatalogApi;
import com.sugarcrumbs.catalog.ItemSnapshot;
import com.sugarcrumbs.common.exception.ConflictException;
import com.sugarcrumbs.common.exception.NotFoundException;
import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.domain.GuestContact;
import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.OrderLine;
import com.sugarcrumbs.ordering.domain.OrderReference;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import com.sugarcrumbs.ordering.persistence.OrderRepository;
import com.sugarcrumbs.ordering.persistence.OrderSpecifications;
import com.sugarcrumbs.ordering.web.dto.CheckoutRequest;
import com.sugarcrumbs.ordering.web.dto.DayAvailabilityResponse;
import com.sugarcrumbs.ordering.web.dto.OrderLineRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional
public class OrderService {

    /** A clash among 33.5M references is already rare; several in a row means something is broken. */
    private static final int REFERENCE_ATTEMPTS = 5;

    private final OrderRepository orderRepository;
    private final CatalogApi catalogApi;
    private final AvailabilityService availabilityService;
    private final OwnerApi ownerApi;

    public OrderService(OrderRepository orderRepository, CatalogApi catalogApi,
                        AvailabilityService availabilityService, OwnerApi ownerApi) {
        this.orderRepository = orderRepository;
        this.catalogApi = catalogApi;
        this.availabilityService = availabilityService;
        this.ownerApi = ownerApi;
    }

    /**
     * Guest checkout. Looks up every item in one batch, copies each one's current name and
     * price into its line, checks the pickup slot, then reserves stock per line. The order's
     * lead time is the longest of its items'.
     *
     * @throws NotFoundException  (404) if an item doesn't exist
     * @throws ConflictException  (409) if an item can't be ordered or the slot isn't bookable
     * @throws IllegalArgumentException (400) for a repeated item or too little notice
     */
    public Order place(CheckoutRequest request) {
        List<UUID> itemIds = request.lines().stream().map(OrderLineRequest::itemId).toList();
        if (new HashSet<>(itemIds).size() != itemIds.size()) {
            throw new IllegalArgumentException("each item may appear only once in an order");
        }
        Map<UUID, ItemSnapshot> items = catalogApi.getItems(itemIds);
        int leadTimeHours = 0;
        for (UUID itemId : itemIds) {
            ItemSnapshot item = items.get(itemId);
            if (item == null) {
                throw NotFoundException.forEntity("Item", itemId);
            }
            if (!item.orderable()) {
                throw new ConflictException("item '" + item.name() + "' is not currently available");
            }
            leadTimeHours = Math.max(leadTimeHours, item.leadTimeHours());
        }
        if (Duration.between(Instant.now(), request.pickupAt()).compareTo(Duration.ofHours(leadTimeHours)) < 0) {
            throw new IllegalArgumentException("this order needs at least " + leadTimeHours + " hours notice");
        }

        ensureSlotIsBookable(request.pickupAt(), null);

        Order.Builder builder = Order.builder()
                .reference(uniqueReference())
                .pickupAt(request.pickupAt())
                .guestContact(new GuestContact(request.guestContact().name(), request.guestContact().email(),
                        request.guestContact().phone()))
                .notes(request.notes());
        for (OrderLineRequest line : request.lines()) {
            ItemSnapshot item = items.get(line.itemId());
            builder.line(item.id(), item.name(), item.price(), line.quantity());
        }
        Order saved = orderRepository.save(builder.build());

        // Capacity (the slot) and stock (physical inventory) are separate constraints;
        // the catalog ignores this for items that don't track stock.
        for (OrderLine line : saved.getLines()) {
            catalogApi.reserveStock(line.getItemId(), line.getQuantity());
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public Order getOrThrow(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> NotFoundException.forEntity("Order", id));
    }

    /** The owner's calendar: any combination of pickup range and status, all optional. */
    @Transactional(readOnly = true)
    public Page<Order> calendar(Instant from, Instant to, OrderStatus status, Pageable pageable) {
        return orderRepository.findAll(OrderSpecifications.combine(from, to, status), pageable);
    }

    public Order confirm(UUID id) {
        Order order = getOrThrow(id);
        order.confirm();
        return order;
    }

    public Order startPreparation(UUID id) {
        Order order = getOrThrow(id);
        order.startPreparation();
        return order;
    }

    public Order fulfill(UUID id) {
        Order order = getOrThrow(id);
        order.fulfill();
        return order;
    }

    /** Covers both rejecting a pending order and cancelling a confirmed one until #16 adds REJECTED. */
    public Order cancel(UUID id, String reason) {
        Order order = getOrThrow(id);
        order.cancel(reason);
        releaseStock(order);
        return order;
    }

    public Order reschedule(UUID id, Instant newPickupAt) {
        Order order = getOrThrow(id);
        ensureSlotIsBookable(newPickupAt, order.getId());
        order.reschedule(newPickupAt);
        return order;
    }

    /**
     * Invoked by {@code StaleOrderCleanupJob}. Cancels any order still {@code PENDING}
     * {@code holdWindow} after it was placed: an Owner who never confirms shouldn't
     * tie up a slot (or stock) another guest could have used.
     */
    public int cancelStalePendingOrders(Duration holdWindow) {
        Instant cutoff = Instant.now().minus(holdWindow);
        List<Order> stale = orderRepository.findAllByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff);
        for (Order order : stale) {
            order.cancel("Automatically cancelled: not confirmed within the hold window");
            releaseStock(order);
        }
        return stale.size();
    }

    private void ensureSlotIsBookable(Instant pickupAt, UUID excludeOrderId) {
        // Slots are local times in the bakery's zone, not the server's.
        ZonedDateTime local = pickupAt.atZone(ownerApi.settings().timeZone());
        LocalDate date = local.toLocalDate();
        LocalTime time = local.toLocalTime().withSecond(0).withNano(0);

        DayAvailabilityResponse day = availabilityService.computeForDate(date, excludeOrderId);
        if (!day.open()) {
            String suffix = day.reason() != null ? " (" + day.reason() + ")" : "";
            throw new ConflictException("not open for orders on " + date + suffix);
        }

        boolean matchesBookableSlot = day.slots().stream()
                .anyMatch(slot -> slot.startTime().equals(time) && slot.bookable());
        if (!matchesBookableSlot) {
            throw new ConflictException(
                    "the requested time is not an available pickup slot — check GET /api/public/availability first");
        }
    }

    private String uniqueReference() {
        for (int attempt = 0; attempt < REFERENCE_ATTEMPTS; attempt++) {
            String reference = OrderReference.next();
            if (!orderRepository.existsByReference(reference)) {
                return reference;
            }
        }
        throw new IllegalStateException("could not generate a unique order reference");
    }

    /** Lines whose item no longer exists in the catalog have nothing to put back. */
    private void releaseStock(Order order) {
        for (OrderLine line : order.getLines()) {
            if (line.getItemId() != null) {
                catalogApi.releaseStock(line.getItemId(), line.getQuantity());
            }
        }
    }
}
