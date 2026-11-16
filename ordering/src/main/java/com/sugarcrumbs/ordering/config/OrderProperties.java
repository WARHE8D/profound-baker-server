package com.sugarcrumbs.ordering.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Order rules the Owner may want to tune without a release ({@code app.order.*}).
 *
 * @param pendingHoldHours   how long an unconfirmed order keeps its slot and stock before
 *                           {@code StaleOrderCleanupJob} cancels it
 * @param maxLines           the most distinct items one checkout may contain
 * @param maxQuantityPerLine the most units of one item a guest may order at once; bigger orders
 *                           are a conversation with the baker, not a checkout
 * @param guestCancelCutoffHours how long before pickup a guest stops being able to cancel through their
 *                               magic link: by then the baking has likely started
 */
@Validated
@ConfigurationProperties("app.order")
public record OrderProperties(
        @DefaultValue("24") @Positive int pendingHoldHours,
        @DefaultValue("20") @Positive int maxLines,
        @DefaultValue("100") @Positive int maxQuantityPerLine,
        @DefaultValue("24") @PositiveOrZero int guestCancelCutoffHours
) {
}
