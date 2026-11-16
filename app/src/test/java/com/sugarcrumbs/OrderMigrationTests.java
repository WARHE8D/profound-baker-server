package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bookings → orders migration (ordering V2, #14) against real legacy data:
 * each test builds its own database at ordering V1, inserts bookings the old way,
 * then migrates. Configured like {@code FlywayModulesConfig}, one Flyway per schema.
 */
class OrderMigrationTests {

    private static final PostgreSQLContainer postgres = AbstractIntegrationTest.POSTGRES;

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        String name = "orders_migration_" + System.nanoTime();
        new JdbcTemplate(dataSource(postgres.getJdbcUrl())).execute("CREATE DATABASE " + name);
        dataSource = dataSource(postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"));
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void eachBookingBecomesAnOrderWithOneSnapshotLineAndKeepsItsInvoice() {
        migrate("catalog", null);
        migrate("ordering", "1");
        UUID category = insert("INSERT INTO catalog.categories (id, created_at, updated_at, version, name) "
                + "VALUES (?, now(), now(), 0, 'Cakes')");
        UUID item = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog.items (id, created_at, updated_at, version, name, price, category_id)
                VALUES (?, now(), now(), 0, 'Lemon cake', 24.50, ?)
                """, item, category);
        UUID older = booking(item, 2, "CONFIRMED", "2026-01-01T10:00:00Z");
        UUID newer = booking(item, 1, "CANCELLED", "2026-02-01T10:00:00Z");
        jdbc.update("""
                INSERT INTO ordering.invoices (id, created_at, updated_at, version, booking_id, subtotal, status)
                VALUES (gen_random_uuid(), now(), now(), 0, ?, 49.00, 'PENDING')
                """, older);

        migrate("ordering", null);

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM ordering.orders WHERE id = ?", older);
        assertThat(order)
                .containsEntry("reference", "SC-L0001")
                .containsEntry("status", "CONFIRMED")
                .containsEntry("guest_email", "guest@example.com");
        assertThat(jdbc.queryForObject("SELECT reference FROM ordering.orders WHERE id = ?", String.class, newer))
                .isEqualTo("SC-L0002");

        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM ordering.order_lines WHERE order_id = ?", older);
        assertThat(line)
                .containsEntry("line_no", 1)
                .containsEntry("item_id", item)
                .containsEntry("item_name", "Lemon cake")
                .containsEntry("quantity", 2);
        assertThat((BigDecimal) line.get("unit_price")).isEqualByComparingTo("24.50");
        assertThat((BigDecimal) line.get("line_total")).isEqualByComparingTo("49.00");

        assertThat(jdbc.queryForObject("SELECT order_id FROM ordering.invoices", UUID.class)).isEqualTo(older);
        // V5 numbers invoices from before numbering, and the sequence carries on after them.
        assertThat(jdbc.queryForObject("SELECT number FROM ordering.invoices", String.class))
                .matches("INV-\\d{4}-0001");
        assertThat(jdbc.queryForObject("SELECT nextval('ordering.invoice_number_seq')", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT to_regclass('ordering.bookings')", String.class)).isNull();
    }

    @Test
    void aBookingWhoseItemIsGoneKeepsItsQuantityUnderAPlaceholder() {
        migrate("catalog", null);
        migrate("ordering", "1");
        UUID missingItem = UUID.randomUUID();
        UUID booking = booking(missingItem, 3, "PENDING", "2026-03-01T10:00:00Z");

        migrate("ordering", null);

        Map<String, Object> line = jdbc.queryForMap("SELECT * FROM ordering.order_lines WHERE order_id = ?", booking);
        assertThat(line)
                .containsEntry("item_id", missingItem)
                .containsEntry("item_name", "(item no longer available)")
                .containsEntry("quantity", 3);
        assertThat((BigDecimal) line.get("line_total")).isEqualByComparingTo("0.00");
    }

    /** An extracted order-service starts on a database with no catalog schema at all. */
    @Test
    void migratesWhereNoCatalogSchemaExists() {
        migrate("ordering", null);

        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'ordering'
                  AND table_name <> 'flyway_schema_history' ORDER BY 1
                """, String.class))
                .containsExactly("blocked_dates", "invoices", "order_lines", "orders", "working_hours");
        assertThat(jdbc.queryForObject("SELECT to_regclass('catalog.items')", String.class)).isNull();
    }

    private UUID booking(UUID itemId, int quantity, String status, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ordering.bookings
                    (id, created_at, updated_at, version, item_id, quantity, scheduled_for, status,
                     guest_name, guest_email, guest_phone)
                VALUES (?, CAST(? AS TIMESTAMPTZ), now(), 0, ?, ?, now() + interval '2 days', ?,
                        'Guest', 'guest@example.com', '555-0100')
                """, id, createdAt, itemId, quantity, status);
        return id;
    }

    private UUID insert(String sql) {
        UUID id = UUID.randomUUID();
        jdbc.update(sql, id);
        return id;
    }

    private void migrate(String schema, String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration/" + schema)
                .failOnMissingLocations(true);
        if (target != null) {
            config.target(target);
        }
        config.load().migrate();
    }

    private static DataSource dataSource(String url) {
        return new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
    }
}
