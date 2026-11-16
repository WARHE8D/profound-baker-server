-- Owner rejection (#16): REJECTED is its own final status, apart from CANCELLED, so reports can tell
-- the Owner turning an order down from a guest or the system calling it off. Its reason gets its own
-- column for the same reason. Orders the Owner cancelled while still pending before this migration
-- stay CANCELLED: nothing recorded which of them were rejections.

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (status IN
    ('PENDING', 'CONFIRMED', 'IN_PROGRESS', 'FULFILLED', 'REJECTED', 'CANCELLED'));

ALTER TABLE orders ADD COLUMN rejection_reason VARCHAR(300);

