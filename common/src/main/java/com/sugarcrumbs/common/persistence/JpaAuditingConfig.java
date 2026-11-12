package com.sugarcrumbs.common.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Turns on {@code @CreatedDate}/{@code @LastModifiedDate} handling for
 * every entity extending {@code BaseEntity}. Without this, those fields
 * stay null.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
