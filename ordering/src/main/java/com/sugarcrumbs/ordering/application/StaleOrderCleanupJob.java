package com.sugarcrumbs.ordering.application;

import com.sugarcrumbs.ordering.config.OrderProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * A {@code PENDING} order holds a pickup slot (and stock, if tracked) from the
 * moment it is placed; nothing else stops a guest waiting forever on an Owner
 * who never responds. This job releases those holds so one unconfirmed order
 * can't block out a real customer indefinitely.
 *
 * <p>Runs every 15 minutes; the hold window is {@code app.order.pending-hold-hours}
 * (default 24h).
 */
@Component
public class StaleOrderCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(StaleOrderCleanupJob.class);

    private final OrderService orderService;
    private final int pendingHoldHours;

    public StaleOrderCleanupJob(OrderService orderService, OrderProperties properties) {
        this.orderService = orderService;
        this.pendingHoldHours = properties.pendingHoldHours();
    }

    @Scheduled(cron = "0 */15 * * * *")
    public void cancelStaleOrders() {
        int cancelled = orderService.cancelStalePendingOrders(Duration.ofHours(pendingHoldHours));
        if (cancelled > 0) {
            log.info("auto-cancelled {} order(s) pending for more than {}h", cancelled, pendingHoldHours);
        }
    }
}
