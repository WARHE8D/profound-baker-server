package com.sugarcrumbs;

import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * One Flyway per module, each owning its own Postgres schema, migration
 * location ({@code db/migration/<module>}, shipped in that module's jar) and
 * history table. This keeps every module's schema independently versioned,
 * so extracting a module into a service means moving its schema to a new
 * database (e.g. {@code pg_dump -n catalog}) with its migration history intact.
 * <p>
 * Defining {@link Flyway} beans makes Boot's single-Flyway auto-configuration
 * back off. Boot still detects the {@link FlywayMigrationInitializer} beans and
 * makes the JPA {@code EntityManagerFactory} wait for all of them, so Hibernate's
 * {@code ddl-auto=validate} always runs against a fully migrated schema.
 * <p>
 * Migrations use unqualified table names: Flyway sets {@code search_path} to the
 * module's schema. Modules have no cross-schema FKs, so the run order doesn't matter.
 */
@Configuration(proxyBeanMethods = false)
class FlywayModulesConfig {

    @Bean
    Flyway identityFlyway(DataSource dataSource) {
        return moduleFlyway(dataSource, "identity");
    }

    @Bean
    Flyway catalogFlyway(DataSource dataSource) {
        return moduleFlyway(dataSource, "catalog");
    }

    @Bean
    Flyway orderingFlyway(DataSource dataSource) {
        return moduleFlyway(dataSource, "ordering");
    }

    @Bean
    FlywayMigrationInitializer identityFlywayInitializer(Flyway identityFlyway) {
        return new FlywayMigrationInitializer(identityFlyway);
    }

    @Bean
    FlywayMigrationInitializer catalogFlywayInitializer(Flyway catalogFlyway) {
        return new FlywayMigrationInitializer(catalogFlyway);
    }

    @Bean
    FlywayMigrationInitializer orderingFlywayInitializer(Flyway orderingFlyway) {
        return new FlywayMigrationInitializer(orderingFlyway);
    }

    private static Flyway moduleFlyway(DataSource dataSource, String schema) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration/" + schema)
                .failOnMissingLocations(true)
                .load();
    }
}
