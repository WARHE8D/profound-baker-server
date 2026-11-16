-- Guest magic link (#18): the SHA-256 hash (hex) of the token returned once at checkout, and when the
-- link stops working (30 days after the order reaches a final state; NULL while it is still open).
-- Orders placed before this migration have no token: their guests never received one.

ALTER TABLE orders ADD COLUMN access_token_hash VARCHAR(64);
ALTER TABLE orders ADD COLUMN access_token_expires_at TIMESTAMPTZ;

ALTER TABLE orders ADD CONSTRAINT uk_orders_access_token_hash UNIQUE (access_token_hash);
ALTER TABLE orders ADD CONSTRAINT ck_orders_access_token_expiry
    CHECK (access_token_expires_at IS NULL OR access_token_hash IS NOT NULL);
