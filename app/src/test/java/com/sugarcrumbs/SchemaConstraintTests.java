package com.sugarcrumbs;

import com.sugarcrumbs.common.test.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The baseline migrations are the last line of defence behind the entities'
 * own invariants: these check that the database itself rejects bad rows, and
 * that each violation surfaces under its named constraint (so error handling
 * and logs can tell them apart). Each test rolls back.
 */
@Transactional
class SchemaConstraintTests extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    String ddlAuto;

    @Test
    void hibernateOnlyValidatesAndNeverChangesTheSchema() {
        assertThat(ddlAuto).isEqualTo("validate");
    }

    @Test
    void ownerEmailIsUnique() {
        insertOwner("baker@example.com");

        assertViolates("uk_owners_email", () -> insertOwner("baker@example.com"));
    }

    @Test
    void categoryNameIsUnique() {
        insertCategory("Cakes");

        assertViolates("uk_categories_name", () -> insertCategory("Cakes"));
    }

    @Test
    void itemPriceMustBePositive() {
        UUID category = insertCategory("Bread");

        assertViolates("ck_items_price_positive", () -> insertItem(category, "0.00", null));
    }

    @Test
    void itemStockCannotGoNegative() {
        UUID category = insertCategory("Pastries");

        assertViolates("ck_items_stock_non_negative", () -> insertItem(category, "3.50", -1));
    }

    @Test
    void itemMustReferenceAnExistingCategory() {
        assertViolates("fk_items_category", () -> insertItem(UUID.randomUUID(), "3.50", null));
    }

    @Test
    void deletingAnItemRemovesItsImageRows() {
        UUID item = insertItem(insertCategory("Cookies"), "2.00", null);
        jdbc.update("INSERT INTO catalog.item_images (item_id, position, image_url) VALUES (?, 0, '/a.png')", item);

        jdbc.update("DELETE FROM catalog.items WHERE id = ?", item);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM catalog.item_images WHERE item_id = ?", Integer.class, item)).isZero();
    }

    @Test
    void workingHoursAreUniquePerOwnerAndDay() {
        UUID owner = UUID.randomUUID();
        insertWorkingHours(owner, "MONDAY", "09:00", "17:00");

        assertViolates("uk_working_hours_owner_day", () -> insertWorkingHours(owner, "MONDAY", "10:00", "12:00"));
    }

    @Test
    void workingHoursWindowMustEndAfterItStarts() {
        assertViolates("ck_working_hours_window",
                () -> insertWorkingHours(UUID.randomUUID(), "TUESDAY", "17:00", "09:00"));
    }

    @Test
    void blockedDateRangeCannotEndBeforeItStarts() {
        assertViolates("ck_blocked_dates_range", () -> jdbc.update("""
                INSERT INTO ordering.blocked_dates
                    (id, created_at, updated_at, version, owner_id, start_date, end_date)
                VALUES (gen_random_uuid(), now(), now(), 0, gen_random_uuid(), DATE '2026-12-25', DATE '2026-12-24')
                """));
    }

    @Test
    void orderStatusMustBeAKnownValue() {
        assertViolates("ck_orders_status", () -> insertOrder("SHIPPED", "SC-AAAA1"));
    }

    @Test
    void orderReferenceIsUnique() {
        insertOrder("PENDING", "SC-AAAA2");

        assertViolates("uk_orders_reference", () -> insertOrder("PENDING", "SC-AAAA2"));
    }

    @Test
    void anOrderLineTotalMustBeUnitPriceTimesQuantity() {
        UUID order = insertOrder("PENDING", "SC-AAAA3");

        assertViolates("ck_order_lines_line_total", () -> insertLine(order, 1, "2.50", 2, "4.00"));
    }

    @Test
    void anOrderLineNumberIsUniqueWithinItsOrder() {
        UUID order = insertOrder("PENDING", "SC-AAAA4");
        insertLine(order, 1, "2.50", 2, "5.00");

        assertViolates("uk_order_lines_order_line_no", () -> insertLine(order, 1, "1.00", 1, "1.00"));
    }

    @Test
    void anOrderLineMustBelongToAnExistingOrder() {
        assertViolates("fk_order_lines_order", () -> insertLine(UUID.randomUUID(), 1, "1.00", 1, "1.00"));
    }

    @Test
    void deletingAnOrderRemovesItsLines() {
        UUID order = insertOrder("PENDING", "SC-AAAA5");
        insertLine(order, 1, "1.00", 1, "1.00");

        jdbc.update("DELETE FROM ordering.orders WHERE id = ?", order);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ordering.order_lines WHERE order_id = ?", Integer.class, order)).isZero();
    }

    @Test
    void orderHasAtMostOneInvoice() {
        UUID order = insertOrder("CONFIRMED", "SC-AAAA6");
        insertInvoice(order);

        assertViolates("uk_invoices_order", () -> insertInvoice(order));
    }

    // ---- helpers ----

    private static void assertViolates(String constraint, Runnable insert) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private void insertOwner(String email) {
        jdbc.update("""
                INSERT INTO identity.owners
                    (id, created_at, updated_at, version, business_name, owner_name, email, password_hash)
                VALUES (gen_random_uuid(), now(), now(), 0, 'SugarCrumbs', 'Owner', ?, 'hash')
                """, email);
    }

    private UUID insertCategory(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog.categories (id, created_at, updated_at, version, name)
                VALUES (?, now(), now(), 0, ?)
                """, id, name);
        return id;
    }

    private UUID insertItem(UUID categoryId, String price, Integer stock) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog.items
                    (id, created_at, updated_at, version, name, price, category_id, stock_quantity)
                VALUES (?, now(), now(), 0, 'Item', CAST(? AS NUMERIC), ?, ?)
                """, id, price, categoryId, stock);
        return id;
    }

    private void insertWorkingHours(UUID ownerId, String day, String start, String end) {
        jdbc.update("""
                INSERT INTO ordering.working_hours
                    (id, created_at, updated_at, version, owner_id, day_of_week, start_time, end_time,
                     slot_duration_minutes, max_concurrent_bookings)
                VALUES (gen_random_uuid(), now(), now(), 0, ?, ?, CAST(? AS TIME), CAST(? AS TIME), 30, 1)
                """, ownerId, day, start, end);
    }

    private UUID insertOrder(String status, String reference) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ordering.orders
                    (id, created_at, updated_at, version, reference, pickup_at, status,
                     guest_name, guest_email, guest_phone)
                VALUES (?, now(), now(), 0, ?, now() + interval '2 days', ?,
                        'Guest', 'guest@example.com', '555-0100')
                """, id, reference, status);
        return id;
    }

    private void insertLine(UUID orderId, int lineNo, String unitPrice, int quantity, String lineTotal) {
        jdbc.update("""
                INSERT INTO ordering.order_lines
                    (id, created_at, updated_at, version, order_id, line_no, item_id, item_name,
                     unit_price, quantity, line_total)
                VALUES (gen_random_uuid(), now(), now(), 0, ?, ?, gen_random_uuid(), 'Item',
                        CAST(? AS NUMERIC), ?, CAST(? AS NUMERIC))
                """, orderId, lineNo, unitPrice, quantity, lineTotal);
    }

    private void insertInvoice(UUID orderId) {
        jdbc.update("""
                INSERT INTO ordering.invoices
                    (id, created_at, updated_at, version, order_id, subtotal, status)
                VALUES (gen_random_uuid(), now(), now(), 0, ?, 10.00, 'PENDING')
                """, orderId);
    }
}
