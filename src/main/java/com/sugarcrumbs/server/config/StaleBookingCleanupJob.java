package com.sugarcrumbs.server.config;

import com.sugarcrumbs.server.service.BookingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A {@code PENDING} booking holds a time slot (and stock, if tracked)
 * the moment it's created — nothing stops a guest from "booking" and
 * then never getting a response if the owner doesn't act. This job
 * reclaims those slots automatically so one unconfirmed request can't
 * block out a real customer indefinitely.
 *
 * <p>Runs every 15 minutes; the hold window itself is configurable via
 * {@code app.booking.pending-hold-hours} (defaults to 24h) so the owner
 * can tune it without a redeploy.
 */
@Component
public class StaleBookingCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(StaleBookingCleanupJob.class);

    private final BookingService bookingService;
    private final int pendingHoldHours;

    public StaleBookingCleanupJob(BookingService bookingService,
                                  @Value("${app.booking.pending-hold-hours:24}") int pendingHoldHours) {
        this.bookingService = bookingService;
        this.pendingHoldHours = pendingHoldHours;
    }

    @Scheduled(cron = "0 */15 * * * *")
    public void cancelStaleBookings() {
        int cancelled = bookingService.cancelStalePendingBookings(Duration.ofHours(pendingHoldHours));
        if (cancelled > 0) {
            log.info("auto-cancelled {} booking(s) pending for more than {}h", cancelled, pendingHoldHours);
        }
    }
}

