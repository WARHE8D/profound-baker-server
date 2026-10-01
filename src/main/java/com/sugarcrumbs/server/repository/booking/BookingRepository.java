package com.sugarcrumbs.server.repository.booking;
import com.sugarcrumbs.server.entity.Booking;
import com.sugarcrumbs.server.entity.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    /** Used by {@code AvailabilityService} to compute remaining slot capacity. */
    long countByScheduledForGreaterThanEqualAndScheduledForLessThanAndStatusNot(
            Instant slotStart, Instant slotEnd, BookingStatus excludedStatus);

    /** Same as above, but leaves one booking out of its own count — used when checking a booking's own new slot during reschedule. */
    long countByScheduledForGreaterThanEqualAndScheduledForLessThanAndStatusNotAndIdNot(
            Instant slotStart, Instant slotEnd, BookingStatus excludedStatus, UUID excludedId);

    /** Used by the stale-booking cleanup job — see {@code StaleBookingCleanupJob}. */
    List<Booking> findAllByStatusAndCreatedAtBefore(BookingStatus status, Instant cutoff);

    boolean existsByItemId(UUID itemId);
}
