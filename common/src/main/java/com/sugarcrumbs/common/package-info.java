/**
 * Shared kernel used by every module: {@code BaseEntity}, JPA auditing, the
 * {@code ApiError} body and global exception handler, common exceptions, and the
 * business time zone.
 *
 * <p>Keep this small and free of business rules. Anything placed here gets copied
 * into every future service (as the {@code libs/common} jar), so a domain concept
 * belongs in its own module instead.
 */
@ApplicationModule(displayName = "Common", type = ApplicationModule.Type.OPEN)
package com.sugarcrumbs.common;

import org.springframework.modulith.ApplicationModule;

