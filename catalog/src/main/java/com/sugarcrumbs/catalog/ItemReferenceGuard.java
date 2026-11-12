package com.sugarcrumbs.catalog;

import java.util.UUID;

/**
 * Extension point for modules that hold references to catalog items (e.g.
 * ordering, through a booking's {@code itemId}). The catalog refuses to delete
 * an item while any guard reports it as referenced.
 *
 * <p>This lets the catalog ask "is this item in use?" without depending on the
 * modules that use it, which would create a dependency cycle. Once orders store
 * item name/price snapshots (#14), deleting an item no longer damages history and
 * this guard can go away in favour of a soft delete.
 */
public interface ItemReferenceGuard {

    boolean isReferenced(UUID itemId);
}
