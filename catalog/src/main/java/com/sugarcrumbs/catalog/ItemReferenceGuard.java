package com.sugarcrumbs.catalog;

import java.util.UUID;

/**
 * Extension point for modules that hold references to catalog items (e.g.
 * ordering, through an order line's {@code itemId}). The catalog refuses to
 * delete an item while any guard reports it as referenced.
 *
 * <p>This lets the catalog ask "is this item in use?" without depending on the
 * modules that use it, which would create a dependency cycle. Order lines keep
 * their own name/price snapshot (#14), so deleting an item no longer damages
 * history; this guard can go away once items are soft-deleted.
 */
public interface ItemReferenceGuard {

    boolean isReferenced(UUID itemId);
}
