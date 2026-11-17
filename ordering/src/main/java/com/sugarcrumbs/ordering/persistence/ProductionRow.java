package com.sugarcrumbs.ordering.persistence;

import java.util.UUID;

/**
 * One row of the production query: an item (by id and the name its lines were snapshotted
 * under) and how many units of it, across how many orders. An item renamed between orders shows
 * up once per name; {@code itemId} is {@code null} for lines whose item was deleted.
 */
public record ProductionRow(UUID itemId, String itemName, long quantity, long orders) {
}
