package com.sugarcrumbs.catalog;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Everything other modules may ask of the catalog. This interface, plus the
 * records next to it, is the catalog's published contract: other modules must
 * not touch {@code Item}, {@code ItemRepository} or anything else under
 * {@code com.sugarcrumbs.catalog.*} (enforced by {@code ModularityTests}).
 *
 * <p>When the catalog becomes its own service, this interface maps 1:1 onto
 * {@code /internal/**} HTTP endpoints and the ordering side swaps the in-process
 * implementation for an HTTP client, without changing any of its callers. Keep it
 * coarse-grained and record-based so that stays true: no entities, no lazy
 * proxies, nothing that assumes a shared transaction or database.
 */
public interface CatalogApi {

    /**
     * @throws com.sugarcrumbs.common.exception.NotFoundException if no such item exists
     */
    ItemSnapshot getItem(UUID itemId);

    /** Batch lookup; ids that don't exist are simply absent from the result. */
    Map<UUID, ItemSnapshot> getItems(Collection<UUID> itemIds);

    /**
     * Takes {@code quantity} units out of stock. A no-op for items that don't
     * track stock.
     *
     * @throws IllegalArgumentException if there isn't enough stock
     */
    void reserveStock(UUID itemId, int quantity);

    /** Puts {@code quantity} units back, e.g. when an order is cancelled. A no-op for untracked items. */
    void releaseStock(UUID itemId, int quantity);
}
