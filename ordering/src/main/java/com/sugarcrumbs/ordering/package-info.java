/**
 * Ordering module: bookings (orders), availability (working hours, blocked dates,
 * slot capacity) and invoices. Future {@code order-service}.
 *
 * <p>Availability lives here rather than in its own module because slot capacity is
 * computed from bookings and must be checked in the same transaction that creates
 * one (see CLAUDE.md §3).
 *
 * <p>Talks to other modules only through {@code catalog.CatalogApi} and
 * {@code identity.OwnerApi}. Nothing depends on this module.
 */
@ApplicationModule(displayName = "Ordering", allowedDependencies = {"common", "catalog", "identity"})
package com.sugarcrumbs.ordering;

import org.springframework.modulith.ApplicationModule;

