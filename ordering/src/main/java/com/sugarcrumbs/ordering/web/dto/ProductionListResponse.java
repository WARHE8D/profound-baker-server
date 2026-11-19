package com.sugarcrumbs.ordering.web.dto;

import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the bakery has to make for one day's pickups: every item across that day's confirmed and
 * in-progress orders. Pending orders are left out until the Owner accepts them.
 *
 * @param date       the day, in the bakery's time zone
 * @param orderCount how many orders those pickups are (not the sum of the items' counts: one order has several)
 * @param items      by name
 */
public record ProductionListResponse(LocalDate date, long orderCount, List<ProductionItemResponse> items) {

    /**
     * @param itemId     {@code null} if the item has since been deleted from the catalog
     * @param itemName   the catalog's current name, or the name the orders recorded if it is gone
     * @param orderCount how many of the day's orders include it
     */
    public record ProductionItemResponse(@Nullable UUID itemId, String itemName, long quantity, long orderCount) {
    }
}
