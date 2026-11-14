-- Owner refresh tokens (#10). Only the SHA-256 hash of each token is stored.
-- A family is every token descended from one login; reuse of a revoked token revokes its family.

CREATE TABLE refresh_tokens (
    id              UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL,
    owner_id        UUID         NOT NULL,
    family_id       UUID         NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by_id  UUID,

    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    -- Same schema (identity), so a real FK is allowed here.
    CONSTRAINT fk_refresh_tokens_owner FOREIGN KEY (owner_id) REFERENCES owners (id) ON DELETE CASCADE
);

CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
