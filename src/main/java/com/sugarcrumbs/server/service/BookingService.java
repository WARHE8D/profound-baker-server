package com.sugarcrumbs.server.service;
import com.sugarcrumbs.server.dto.request.BookingCreateRequest;
import com.sugarcrumbs.server.dto.response.DayAvailabilityResponse;
import com.sugarcrumbs.server.entity.Booking;
import com.sugarcrumbs.server.entity.BookingStatus;
import com.sugarcrumbs.server.entity.GuestContact;
import com.sugarcrumbs.server.entity.Item;
import com.sugarcrumbs.server.exception.ConflictException;
import com.sugarcrumbs.server.exception.NotFoundException;
import com.sugarcrumbs.server.repository.booking.BookingRepository;
import com.sugarcrumbs.server.repository.booking.BookingSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.sugarcrumbs.server.util.BusinessTimeZone.ZONE;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;


@Service
@Transactional
public class BookingService {

    private final BookingRepository bookingRepository;
    private final ItemService itemService;
    private final AvailabilityService availabilityService;

    public BookingService(BookingRepository bookingRepository, ItemService itemService,
                          AvailabilityService availabilityService) {
        this.bookingRepository = bookingRepository;
        this.itemService = itemService;
        this.availabilityService = availabilityService;
    }

    public Booking create(BookingCreateRequest request) {
        Item item = itemService.getOrThrow(request.itemId());

        if (!item.isOrderable()) {
            throw new ConflictException("item '" + item.getName() + "' is not currently available");
        }

        long hoursNotice = Duration.between(Instant.now(), request.scheduledFor()).toHours();
        if (hoursNotice < item.getLeadTimeHours()) {
            throw new IllegalArgumentException(
                    "item '" + item.getName() + "' requires at least " + item.getLeadTimeHours() + " hours notice");
        }

        ensureSlotIsBookable(request.scheduledFor(), null);

        GuestContact guestContact = new GuestContact(
                request.guestContact().name(), request.guestContact().email(), request.guestContact().phone());

        Booking booking = Booking.builder()
                .item(item)
                .quantity(request.quantity())
                .scheduledFor(request.scheduledFor())
                .guestContact(guestContact)
                .notes(request.notes())
                .build();
        Booking saved = bookingRepository.save(booking);

        // Capacity (time slot) and stock (physical inventory) are two separate constraints;
        // only items that track stock have anything to decrement here.
        if (item.getStockQuantity() != null) {
            item.adjustStock(-request.quantity());
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public Booking getOrThrow(UUID id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> NotFoundException.forEntity("Booking", id));
    }

    /** Backs the owner's calendar view — any combination of date range and status, both optional. */
    @Transactional(readOnly = true)
    public Page<Booking> calendar(Instant from, Instant to, BookingStatus status, Pageable pageable) {
        return bookingRepository.findAll(BookingSpecifications.combine(from, to, status), pageable);
    }

    public Booking confirm(UUID id) {
        Booking booking = getOrThrow(id);
        booking.confirm();
        return booking;
    }

    public Booking startPreparation(UUID id) {
        Booking booking = getOrThrow(id);
        booking.startPreparation();
        return booking;
    }

    public Booking fulfill(UUID id) {
        Booking booking = getOrThrow(id);
        booking.fulfill();
        return booking;
    }

    /** Covers both "owner rejects a pending request" and "owner/guest cancels a confirmed one" — same transition, same cleanup. */
    public Booking cancel(UUID id, String reason) {
        Booking booking = getOrThrow(id);
        booking.cancel(reason);
        releaseStockIfTracked(booking);
        return booking;
    }

    public Booking reschedule(UUID id, Instant newScheduledFor) {
        Booking booking = getOrThrow(id);
        ensureSlotIsBookable(newScheduledFor, booking.getId());
        booking.reschedule(newScheduledFor);
        return booking;
    }

    /**
     * Invoked by {@code StaleBookingCleanupJob}. Cancels any booking still
     * {@code PENDING} after {@code holdWindow} has passed since it was
     * created — an owner who never confirms shouldn't permanently tie up
     * a slot (or stock) another guest could have used.
     */
    public int cancelStalePendingBookings(Duration holdWindow) {
        Instant cutoff = Instant.now().minus(holdWindow);
        List<Booking> stale = bookingRepository.findAllByStatusAndCreatedAtBefore(BookingStatus.PENDING, cutoff);
        for (Booking booking : stale) {
            booking.cancel("Automatically cancelled: not confirmed within the hold window");
            releaseStockIfTracked(booking);
        }
        return stale.size();
    }

    private void ensureSlotIsBookable(Instant scheduledFor, UUID excludeBookingId) {
        LocalDate date = scheduledFor.atZone(ZONE).toLocalDate();
        LocalTime time = scheduledFor.atZone(ZONE).toLocalTime().withSecond(0).withNano(0);

        DayAvailabilityResponse day = availabilityService.computeForDate(date, excludeBookingId);
        if (!day.open()) {
            String suffix = day.reason() != null ? " (" + day.reason() + ")" : "";
            throw new ConflictException("not open for booking on " + date + suffix);
        }

        boolean matchesBookableSlot = day.slots().stream()
                .anyMatch(slot -> slot.startTime().equals(time) && slot.bookable());
        if (!matchesBookableSlot) {
            throw new ConflictException(
                    "the requested time is not an available booking slot — check GET /api/availability first");
        }
    }

    private void releaseStockIfTracked(Booking booking) {
        Item item = booking.getItem();
        if (item.getStockQuantity() != null) {
            item.adjustStock(booking.getQuantity());
        }
    }
}

