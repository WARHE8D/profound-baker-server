/**
 * Identity module: the bakery's single Owner and its authentication (login, JWTs, refresh tokens).
 * Future {@code auth-service}.
 *
 * <p>Published API: {@link com.sugarcrumbs.identity.OwnerApi}. Every sub-package is internal.
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = "common")
package com.sugarcrumbs.identity;

import org.springframework.modulith.ApplicationModule;

