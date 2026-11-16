-- Invoice numbers (#20): INV-YYYY-NNNN, YYYY the year it was issued, NNNN from one sequence that never
-- restarts (so numbers are unique for ever, and simply grow past 9999). A sequence is not
-- transactional: a confirm that rolls back after drawing a number leaves a gap.

CREATE SEQUENCE invoice_number_seq;

ALTER TABLE invoices ADD COLUMN number VARCHAR(20);

-- Invoices from before numbering get theirs in the order they were created, and the sequence carries on.
WITH numbered AS (
    SELECT id, created_at, row_number() OVER (ORDER BY created_at, id) AS n FROM invoices
)
UPDATE invoices i
   SET number = 'INV-' || to_char(numbered.created_at AT TIME ZONE 'UTC', 'YYYY') || '-'
                || lpad(numbered.n::text, greatest(4, length(numbered.n::text)), '0')
  FROM numbered
 WHERE i.id = numbered.id;

SELECT setval('invoice_number_seq', (SELECT count(*) FROM invoices) + 1, false);

ALTER TABLE invoices ALTER COLUMN number SET NOT NULL;
ALTER TABLE invoices ADD CONSTRAINT uk_invoices_number UNIQUE (number);
