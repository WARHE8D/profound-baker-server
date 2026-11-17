-- Offline payments only (#21): CASH or BANK_TRANSFER. E_TRANSFER was a bank transfer under another name.
-- ONLINE_CARD and OTHER were never reachable (no API marked invoices paid before this), so any such row
-- was written by hand: stop rather than guess what it should become.

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM invoices WHERE payment_method IN ('ONLINE_CARD', 'OTHER')) THEN
        RAISE EXCEPTION 'invoices paid by ONLINE_CARD or OTHER need a decision before migrating to offline-only payments';
    END IF;
END $$;

-- The old constraint doesn't know BANK_TRANSFER, so it goes before the rename.
ALTER TABLE invoices DROP CONSTRAINT ck_invoices_payment_method;
UPDATE invoices SET payment_method = 'BANK_TRANSFER' WHERE payment_method = 'E_TRANSFER';
ALTER TABLE invoices ADD CONSTRAINT ck_invoices_payment_method CHECK (payment_method IN ('CASH', 'BANK_TRANSFER'));

-- A paid (or since refunded) invoice says how and when it was paid.
ALTER TABLE invoices ADD CONSTRAINT ck_invoices_paid_has_method
    CHECK (status NOT IN ('PAID', 'REFUNDED') OR (payment_method IS NOT NULL AND paid_at IS NOT NULL));

ALTER TABLE invoices ADD COLUMN refunded_at TIMESTAMPTZ;

CREATE INDEX idx_invoices_created_at ON invoices (created_at);
