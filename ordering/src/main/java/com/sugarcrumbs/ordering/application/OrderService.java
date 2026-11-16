package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.catalog.CatalogApi;
import com.sugarcrumbs.catalog.ItemSnapshot;
import com.sugarcrumbs.common.exception.ConflictException;
import com.sugarcrumbs.common.exception.NotFoundException;
import com.sugarcrumbs.identity.OwnerApi;
import com.sugarcrumbs.ordering.OrderCancelled;
import com.sugarcrumbs.ordering.OrderCancelled.CancelledBy;
import com.sugarcrumbs.ordering.OrderConfirmed;
import com.sugarcrumbs.ordering.OrderEvent;
import com.sugarcrumbs.ordering.OrderFulfilled;
import com.sugarcrumbs.ordering.OrderPlaced;
import com.sugarcrumbs.ordering.OrderRejected;
import com.sugarcrumbs.ordering.OrderRescheduled;
import com.sugarcrumbs.ordering.config.OrderProperties;
import com.sugarcrumbs.ordering.domain.GuestContact;
import com.sugarcrumbs.ordering.domain.Order;
import com.sugarcrumbs.ordering.domain.OrderLine;
import com.sugarcrumbs.ordering.domain.OrderReference;
import com.sugarcrumbs.ordering.domain.OrderStatus;
import com.sugarcrumbs.ordering.persistence.OrderRepository;
import com.sugarcrumbs.ordering.persistence.OrderSpecifications;
import com.sugarcrumbs.ordering.persistence.WorkingHoursRepository;
import com.sugarcrumbs.ordering.web.dto.CheckoutRequest;
import com.sugarcrumbs.ordering.web.dto.DayAvailabilityResponse;
import com.sugarcrumbs.ordering.web.dto.OrderLineRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Orders from checkout to pickup. Every lifecycle change publishes its {@link OrderEvent} inside
 * the same transaction, so listeners that wait for commit see exactly the changes that stuck.
 */
@Service
@Transactional
public class OrderService {

    /** A clash among 33.5M references is already rare; several in a row means something is broken. */
    private static final int REFERENCE_ATTEMPTS = 5;

    /** Never mentions the token: it would end up in a log line or a screenshot. */
    private static final Supplier<NotFoundException> NOT_FOUND_BY_TOKEN =
            () -> new NotFoundException("no order found for this link; it may have expired");

    /** Longer than any reference, name, email or phone it could match. */
    static final int MAX_SEARCH_LENGTH = 100;

    private final OrderRepository orderRepository;
    private final WorkingHoursRepository workingHoursRepository;
    private final CatalogApi catalogApi;
    private final AvailabilityService availabilityService;
    private final OwnerApi ownerApi;
    private final OrderProperties properties;
    private final ApplicationEventPublisher events;
    private final GuestAccessTokens accessTokens;
    private final InvoiceService invoiceService;

    public OrderService(OrderRepository orderRepository, WorkingHoursRepository workingHoursRepository,
                        CatalogApi catalogApi, AvailabilityService availabilityService, OwnerApi ownerApi,
                        OrderProperties properties, ApplicationEventPublisher events,
                        GuestAccessTokens accessTokens, InvoiceService invoiceService) {
        this.orderRepository = orderRepository;
        this.workingHoursRepository = workingHoursRepository;
        this.catalogApi = catalogApi;
        this.availabilityService = availabilityService;
        this.ownerApi = ownerApi;
        this.properties = properties;
        this.events = events;
        this.accessTokens = accessTokens;
        this.invoiceService = invoiceService;
    }

    /**
     * Guest checkout. Checks the order's size against {@code app.order.*}, looks up every item
     * in one batch, copies each one's current name and price into its line, checks the pickup
     * slot under a lock (see {@link #ensureSlotIsBookable}), then reserves stock per line. The
     * order's lead time is the longest of its items'.
     *
     * <p>Also issues the guest's magic-link token. Only its hash is kept, so the returned
     * {@link PlacedOrder} is the one and only time the token itself exists outside the guest's hands.
     *
     * @throws NotFoundException  (404) if an item doesn't exist
     * @throws ConflictException  (409) if an item can't be ordered or the slot isn't bookable
     * @throws IllegalArgumentException (400) for too many lines or units, a repeated item, a pickup
     *                                  time that isn't in the future, too little notice or too little stock
     */
    public PlacedOrder place(CheckoutRequest request) {
        checkLimits(request.lines());
        requireFuture(request.pickupAt());
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
        Order order = builder.build();
        String accessToken = accessTokens.newToken();
        order.issueAccessToken(accessTokens.hash(accessToken));
        Order saved = orderRepository.save(order);

        // Capacity (the slot) and stock (physical inventory) are separate constraints;
        // the catalog ignores this for items that don't track stock.
        for (OrderLine line : saved.getLines()) {
            catalogApi.reserveStock(line.getItemId(), line.getQuantity());
        }
        publish(new OrderPlaced(saved.getId(), saved.getReference(), saved.getPickupAt(), saved.getSubtotal(),
                Instant.now()));
        return new PlacedOrder(saved, accessToken);
    }

    @Transactional(readOnly = true)
    public Order getOrThrow(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> NotFoundException.forEntity("Order", id));
    }

    /**
     * The Owner's calendar and order list: pickup range (either end may be open), any of
     * several statuses and a free-text search on the reference and guest contact, all optional.
     *
     * @throws IllegalArgumentException (400) if {@code from} isn't before {@code to}, or the search is too long
     */
    @Transactional(readOnly = true)
    public Page<Order> calendar(Instant from, Instant to, Collection<OrderStatus> statuses, String search,
                                Pageable pageable) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (search != null && search.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("search may be at most " + MAX_SEARCH_LENGTH + " characters");
        }
        return orderRepository.findAll(OrderSpecifications.combine(from, to, statuses, search), pageable);
    }

    /** Accepts a pending order and issues its invoice (#20). */
    public Order confirm(UUID id) {
        Order order = getOrThrow(id);
        order.confirm();
        invoiceService.issueFor(order);
        publish(new OrderConfirmed(order.getId(), order.getReference(), order.getPickupAt(), Instant.now()));
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
        publish(new OrderFulfilled(order.getId(), order.getReference(), Instant.now()));
        return order;
    }

    /**
     * The Owner turns down a pending order; its slot and stock go back at once.
     *
     * @throws IllegalStateException (400) if the order isn't pending
     */
    public Order reject(UUID id, String reason) {
        Order order = getOrThrow(id);
        order.reject(reason);
        releaseStock(order);
        publish(new OrderRejected(order.getId(), order.getReference(), order.getRejectionReason(), Instant.now()));
        return order;
    }

    /**
     * The Owner calls off an order they had confirmed. A pending one is rejected instead, so
     * reports never count an Owner's refusal as a cancellation.
     *
     * @throws IllegalStateException (400) if the order is pending, or already finished
     */
    public Order cancel(UUID id, String reason) {
        Order order = getOrThrow(id);
        if (order.getStatus() == OrderStatus.PENDING) {
            throw new IllegalStateException("a pending order is rejected, not cancelled");
        }
        order.cancel(reason);
        releaseStock(order);
        invoiceService.voidIfPending(order);
        publishCancelled(order, CancelledBy.OWNER);
        return order;
    }

    /** @throws IllegalStateException (400) unless the order is pending */
    public Order updateNotes(UUID id, String notes) {
        Order order = getOrThrow(id);
        order.updateNotes(notes == null || notes.isBlank() ? null : notes.strip());
        return order;
    }

    /**
     * Replaces a pending order's lines with {@code lines}, the full list wanted (see
     * {@link Order#editLines}). Items already on the order keep the price the guest saw; new
     * ones are snapshotted from the catalog now and must be orderable. The checkout limits still
     * apply, but, as with a reschedule, the Owner may waive lead time. Stock moves by the
     * difference only, so an unchanged line neither takes nor returns any.
     *
     * @throws NotFoundException  (404) if a new item doesn't exist
     * @throws ConflictException  (409) if a new item can't be ordered
     * @throws IllegalArgumentException (400) for too many lines or units, a repeated item or too little stock
     * @throws IllegalStateException    (400) unless the order is pending
     */
    public Order editLines(UUID id, List<OrderLineRequest> lines) {
        checkLimits(lines);
        Order order = getOrThrow(id);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("cannot edit the lines of an order in status " + order.getStatus());
        }
        Map<UUID, Integer> before = quantitiesByItem(order);

        Map<UUID, OrderLine> onOrder = new HashMap<>();
        order.getLines().stream().filter(line -> line.getItemId() != null)
                .forEach(line -> onOrder.put(line.getItemId(), line));
        List<UUID> newItemIds = lines.stream().map(OrderLineRequest::itemId)
                .filter(itemId -> !onOrder.containsKey(itemId)).distinct().toList();
        Map<UUID, ItemSnapshot> newItems = newItemIds.isEmpty() ? Map.of() : catalogApi.getItems(newItemIds);

        List<Order.LineDraft> drafts = new ArrayList<>();
        for (OrderLineRequest line : lines) {
            OrderLine existing = onOrder.get(line.itemId());
            if (existing != null) {
                drafts.add(new Order.LineDraft(existing.getItemId(), existing.getItemName(), existing.getUnitPrice(),
                        line.quantity()));
                continue;
            }
            ItemSnapshot item = newItems.get(line.itemId());
            if (item == null) {
                throw NotFoundException.forEntity("Item", line.itemId());
            }
            if (!item.orderable()) {
                throw new ConflictException("item '" + item.name() + "' is not currently available");
            }
            drafts.add(new Order.LineDraft(item.id(), item.name(), item.price(), line.quantity()));
        }
        order.editLines(drafts);

        Map<UUID, Integer> after = quantitiesByItem(order);
        Set<UUID> touched = new HashSet<>(before.keySet());
        touched.addAll(after.keySet());
        for (UUID itemId : touched) {
            int delta = after.getOrDefault(itemId, 0) - before.getOrDefault(itemId, 0);
            if (delta > 0) {
                catalogApi.reserveStock(itemId, delta);
            } else if (delta < 0) {
                catalogApi.releaseStock(itemId, -delta);
            }
        }
        return order;
    }

    /**
     * Moves an order to another slot. The Owner may waive the items' lead time, but the new
     * slot must still be in the future and have room.
     */
    public Order reschedule(UUID id, Instant newPickupAt) {
        requireFuture(newPickupAt);
        Order order = getOrThrow(id);
        ensureSlotIsBookable(newPickupAt, order.getId());
        Instant previous = order.getPickupAt();
        order.reschedule(newPickupAt);
        if (!previous.equals(newPickupAt)) {
            publish(new OrderRescheduled(order.getId(), order.getReference(), previous, newPickupAt, Instant.now()));
        }
        return order;
    }

    // ---- The guest's magic link (#19) ----

    /**
     * The order a guest's magic link opens. Every way a link can fail (malformed, unknown,
     * expired, from before links existed) is the same 404, so a caller learns nothing about
     * which tokens or orders exist.
     *
     * @throws NotFoundException (404) unless {@code token} opens an order right now
     */
    @Transactional(readOnly = true)
    public Order findByAccessToken(String token) {
        return openByAccessToken(token);
    }

    /**
     * The guest calls their order off: only while it is pending or confirmed, and no later than
     * {@code app.order.guest-cancel-cutoff-hours} before pickup. Releases its slot and stock.
     *
     * @throws NotFoundException (404) unless {@code token} opens an order right now
     * @throws ConflictException (409) if the order is past the point a guest may cancel it
     */
    public Order cancelByGuest(String token, String reason) {
        Order order = openByAccessToken(token);
        Instant until = guestCancellableUntil(order).orElseThrow(() -> new ConflictException(
                "an order in status " + order.getStatus() + " can no longer be cancelled online"));
        if (!Instant.now().isBefore(until)) {
            throw new ConflictException("online cancellation closes " + properties.guestCancelCutoffHours()
                    + " hours before pickup; please contact the bakery");
        }
        order.cancel(reason == null || reason.isBlank() ? "Cancelled by the guest" : reason.strip());
        releaseStock(order);
        invoiceService.voidIfPending(order);
        publishCancelled(order, CancelledBy.GUEST);
        return order;
    }

    /** See {@link Order#guestCancellableUntil}; the cutoff is {@code app.order.guest-cancel-cutoff-hours}. */
    public Optional<Instant> guestCancellableUntil(Order order) {
        return order.guestCancellableUntil(Duration.ofHours(properties.guestCancelCutoffHours()));
    }

    /**
     * Invoked by {@code ExpiredAccessTokenPurgeJob}.
     *
     * @return how many orders' links were forgotten
     */
    public int purgeExpiredAccessTokens() {
        return orderRepository.purgeExpiredAccessTokens(Instant.now());
    }

    /**
     * Looks the order up by the token's hash (the index does the finding), then compares the
     * hashes again in constant time, so even the final check takes the same time however many
     * characters match.
     */
    private Order openByAccessToken(String token) {
        if (token == null || !GuestAccessTokens.looksLikeAToken(token)) {
            throw NOT_FOUND_BY_TOKEN.get();
        }
        String hash = accessTokens.hash(token);
        Order order = orderRepository.findByAccessTokenHash(hash).orElseThrow(NOT_FOUND_BY_TOKEN);
        boolean sameHash = MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
                order.getAccessTokenHash().getBytes(StandardCharsets.US_ASCII));
        if (!sameHash || !order.isAccessTokenValid(Instant.now())) {
            throw NOT_FOUND_BY_TOKEN.get();
        }
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
            // Never confirmed, so never invoiced: nothing to void.
            publishCancelled(order, CancelledBy.SYSTEM);
        }
        return stale.size();
    }

    /** Checked before any catalog lookup, so an oversized order costs nothing but this loop. */
    private void checkLimits(List<OrderLineRequest> lines) {
        if (lines.size() > properties.maxLines()) {
            throw new IllegalArgumentException(
                    "an order may contain at most " + properties.maxLines() + " different items");
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).quantity() > properties.maxQuantityPerLine()) {
                throw new IllegalArgumentException("line " + (i + 1) + ": at most "
                        + properties.maxQuantityPerLine() + " of one item per order");
            }
        }
    }

    /**
     * Checked here as well as by {@code @Future} on the request: an item with no lead time
     * would otherwise let a guest book a slot that has already started, and the service
     * must not depend on its caller having validated.
     */
    private static void requireFuture(Instant pickupAt) {
        if (!pickupAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("pickupAt must be in the future");
        }
    }

    /**
     * Rejects a pickup time that isn't the start of a slot with room left.
     *
     * <p>Counting a slot's orders and then inserting one is a check-then-act race: two guests
     * could both see the last place free. So this first locks the day's {@code WorkingHours} row
     * ({@link WorkingHoursRepository#findForUpdate}) and counts only once it holds the lock; the
     * lock lasts until the caller's transaction commits its order, so the next checkout or
     * reschedule into that day waits and then sees it. The lock is per weekday, which serialises
     * every booking on, say, all Tuesdays: one bakery's checkouts are rare enough for that not to
     * matter, and it's the row that holds the capacity. A closed day has no row to lock, and is
     * rejected below anyway.
     */
    private void ensureSlotIsBookable(Instant pickupAt, UUID excludeOrderId) {
        // Slots are local times in the bakery's zone, not the server's.
        ZonedDateTime local = pickupAt.atZone(ownerApi.settings().timeZone());
        LocalDate date = local.toLocalDate();
        LocalTime time = local.toLocalTime().withSecond(0).withNano(0);

        workingHoursRepository.findForUpdate(ownerApi.currentOwnerId(), date.getDayOfWeek());
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

    private void publishCancelled(Order order, CancelledBy by) {
        publish(new OrderCancelled(order.getId(), order.getReference(), by, order.getCancellationReason(),
                Instant.now()));
    }

    private void publish(OrderEvent event) {
        events.publishEvent(event);
    }

    /** Lines whose item no longer exists in the catalog are left out: there is no stock to move. */
    private static Map<UUID, Integer> quantitiesByItem(Order order) {
        Map<UUID, Integer> quantities = new HashMap<>();
        for (OrderLine line : order.getLines()) {
            if (line.getItemId() != null) {
                quantities.put(line.getItemId(), line.getQuantity());
            }
        }
        return quantities;
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
