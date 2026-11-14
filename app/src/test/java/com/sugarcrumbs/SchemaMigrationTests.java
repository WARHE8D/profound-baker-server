package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every module's migrations apply cleanly to an empty Postgres, and Hibernate's
 * {@code ddl-auto=validate} accepts the result. Validation runs while the context
 * starts, so a mapping/migration mismatch fails the context and this test with it.
 */
class SchemaMigrationTests extends AbstractIntegrationTest {

    @Autowired
    Map<String, Flyway> flyways;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void eachModuleMigratesItsOwnSchemaWithNothingPending() {
        assertThat(flyways.values())
                .extracting(f -> f.getConfiguration().getDefaultSchema())
                .containsExactlyInAnyOrder("identity", "catalog", "ordering");

        flyways.values().forEach(flyway -> {
            var info = flyway.info();
            assertThat(info.current()).as(flyway.getConfiguration().getDefaultSchema()).isNotNull();
            assertThat(info.pending()).isEmpty();
            assertThat(info.applied()).allMatch(m -> m.getState().isApplied() && !m.getState().isFailed());
        });
    }

    @Test
    void tablesLiveInTheirModuleSchemaAndNothingInPublic() {
        List<String> tables = jdbc.queryForList("""
                SELECT table_schema || '.' || table_name
                FROM information_schema.tables
                WHERE table_schema IN ('public', 'identity', 'catalog', 'ordering')
                  AND table_name <> 'flyway_schema_history'
                ORDER BY 1
                """, String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "identity.owners", "identity.refresh_tokens",
                "catalog.categories", "catalog.items", "catalog.item_images",
                "ordering.working_hours", "ordering.blocked_dates", "ordering.bookings", "ordering.invoices");
    }

    @Test
    void noForeignKeyCrossesAModuleSchema() {
        Integer crossSchemaFks = jdbc.queryForObject("""
                SELECT count(*)
                FROM pg_constraint c
                JOIN pg_class src ON src.oid = c.conrelid
                JOIN pg_class dst ON dst.oid = c.confrelid
                WHERE c.contype = 'f' AND src.relnamespace <> dst.relnamespace
                """, Integer.class);

        assertThat(crossSchemaFks).isZero();
    }
}
