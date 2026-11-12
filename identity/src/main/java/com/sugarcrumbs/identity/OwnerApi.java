package com.sugarcrumbs.identity;

import java.util.UUID;

/**
 * What other modules may ask about the Owner. They hold the Owner's id as a
 * plain UUID and never reference the {@code Owner} entity, so the Owner can
 * move to its own service (auth-service) and database.
 */
public interface OwnerApi {

    /**
     * The id of the bakery's single Owner.
     *
     * @throws IllegalStateException if no Owner has been set up yet
     */
    UUID currentOwnerId();
}
