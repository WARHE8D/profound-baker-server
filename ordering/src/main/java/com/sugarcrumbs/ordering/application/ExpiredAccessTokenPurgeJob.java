package com.sugarcrumbs.ordering.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly, forgets the hashes of magic links that have expired (#19). An expired link already
 * opens nothing; dropping its hash means a leaked database doesn't even say which hash once
 * opened which finished order.
 */
@Component
public class ExpiredAccessTokenPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiredAccessTokenPurgeJob.class);

    private final OrderService orderService;

    public ExpiredAccessTokenPurgeJob(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpiredAccessTokens() {
        int purged = orderService.purgeExpiredAccessTokens();
        if (purged > 0) {
            log.info("forgot {} expired magic link(s)", purged);
        }
    }
}
