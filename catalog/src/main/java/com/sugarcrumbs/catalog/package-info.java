/**
 * Catalog module: items, categories and their images, i.e. what the storefront shows.
 * Future {@code catalog-service}.
 *
 * <p>Published API: the types in this package ({@link com.sugarcrumbs.catalog.CatalogApi},
 * {@link com.sugarcrumbs.catalog.ItemSnapshot}, {@link com.sugarcrumbs.catalog.ItemReferenceGuard}).
 * Every sub-package is internal.
 */
@ApplicationModule(displayName = "Catalog", allowedDependencies = "common")
package com.sugarcrumbs.catalog;

import org.springframework.modulith.ApplicationModule;

