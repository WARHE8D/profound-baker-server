package com.sugarcrumbs.identity;

import java.time.ZoneId;

/**
 * Business settings other modules need from the Owner. A plain record so it can
 * later come from {@code GET /internal/owner/settings} unchanged.
 *
 * @param timeZone the bakery's zone: working hours, slots and pickup dates are local times in it
 */
public record OwnerSettings(ZoneId timeZone) {
}
