-- Multi-item orders (#14): bookings become orders, each item becomes an order line carrying its own
-- name/price snapshot, so repricing an item never changes a past order. Runs with search_path = ordering.

CREATE TABLE orders (
    id                   UUID          NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    version              BIGINT        NOT NULL,
    reference            VARCHAR(16)   NOT NULL,
    pickup_at            TIMESTAMPTZ   NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    -- GuestContact (embedded value object)
    guest_name           VARCHAR(150)  NOT NULL,
    guest_email          VARCHAR(254)  NOT NULL,
    guest_phone          VARCHAR(30)   NOT NULL,
    notes                VARCHAR(1000),
    cancellation_reason  VARCHAR(300),

    CONSTRAINT pk_orders PRIMARY KEY (id),
    CONSTRAINT uk_orders_reference UNIQUE (reference),
    CONSTRAINT ck_orders_status CHECK (status IN
        ('PENDING', 'CONFIRMED', 'IN_PROGRESS', 'FULFILLED', 'CANCELLED'))
);

CREATE INDEX idx_orders_pickup_at ON orders (pickup_at);
CREATE INDEX idx_orders_status ON orders (status);

-- item_id points at catalog: a plain UUID with no FK (CLAUDE.md §3), nullable so a line can outlive its item.
CREATE TABLE order_lines (
    id          UUID           NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    version     BIGINT         NOT NULL,
    order_id    UUID           NOT NULL,
    line_no     INTEGER        NOT NULL,
    item_id     UUID,
    item_name   VARCHAR(150)   NOT NULL,
    unit_price  NUMERIC(10,2)  NOT NULL,
    quantity    INTEGER        NOT NULL,
    line_total  NUMERIC(10,2)  NOT NULL,

    CONSTRAINT pk_order_lines PRIMARY KEY (id),
    CONSTRAINT fk_order_lines_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT uk_order_lines_order_line_no UNIQUE (order_id, line_no),
    CONSTRAINT ck_order_lines_line_no CHECK (line_no >= 1),
    CONSTRAINT ck_order_lines_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_order_lines_unit_price CHECK (unit_price >= 0),
    CONSTRAINT ck_order_lines_line_total CHECK (line_total = unit_price * quantity)
);

CREATE INDEX idx_order_lines_item ON order_lines (item_id);

-- Every booking becomes an order with the same id (so invoices keep pointing at it) and a legacy
-- reference SC-L0001, SC-L0002, … in creation order: unique, and visibly from before references existed.
INSERT INTO orders (id, created_at, updated_at, version, reference, pickup_at, status,
                    guest_name, guest_email, guest_phone, notes, cancellation_reason)
SELECT id, created_at, updated_at, version,
       'SC-L' || lpad(rn::text, greatest(4, length(rn::text)), '0'),
       scheduled_for, status, guest_name, guest_email, guest_phone, notes, cancellation_reason
FROM (SELECT b.*, row_number() OVER (ORDER BY b.created_at, b.id) AS rn FROM bookings b) numbered;

-- One line per booking. The name/price snapshot is the item's as of this migration (bookings never
-- stored one). Reading catalog.items is a deliberate one-off exception to "never query another
-- module's schema" (agreed for #14): it happens once, before any extraction, and nothing at runtime
-- does it. Guarded so the migration still runs where no catalog schema exists (an extracted
-- order-service on a fresh database, which has no bookings anyway); EXECUTE defers name resolution.
DO $$
BEGIN
    IF to_regclass('catalog.items') IS NOT NULL THEN
        EXECUTE $sql$
            INSERT INTO order_lines (id, created_at, updated_at, version, order_id, line_no,
                                     item_id, item_name, unit_price, quantity, line_total)
            SELECT gen_random_uuid(), b.created_at, b.updated_at, 0, b.id, 1,
                   b.item_id, i.name, i.price, b.quantity, i.price * b.quantity
            FROM bookings b
            JOIN catalog.items i ON i.id = b.item_id
        $sql$;
    END IF;
END $$;

-- Any booking whose item could not be found keeps its quantity under a placeholder name and no price.
INSERT INTO order_lines (id, created_at, updated_at, version, order_id, line_no,
                         item_id, item_name, unit_price, quantity, line_total)
SELECT gen_random_uuid(), b.created_at, b.updated_at, 0, b.id, 1,
       b.item_id, '(item no longer available)', 0.00, b.quantity, 0.00
FROM bookings b
WHERE NOT EXISTS (SELECT 1 FROM order_lines l WHERE l.order_id = b.id);

-- Invoices now belong to orders. Ids were kept, so existing rows stay valid.
ALTER TABLE invoices DROP CONSTRAINT fk_invoices_booking;
ALTER TABLE invoices RENAME COLUMN booking_id TO order_id;
ALTER TABLE invoices RENAME CONSTRAINT uk_invoices_booking TO uk_invoices_order;
ALTER TABLE invoices ADD CONSTRAINT fk_invoices_order FOREIGN KEY (order_id) REFERENCES orders (id);

DROP TABLE bookings;
