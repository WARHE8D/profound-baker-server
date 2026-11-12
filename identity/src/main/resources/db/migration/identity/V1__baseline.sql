-- identity module baseline. Runs with search_path = identity (see FlywayModulesConfig).

CREATE TABLE owners (
    id             UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL,
    business_name  VARCHAR(150) NOT NULL,
    owner_name     VARCHAR(150) NOT NULL,
    email          VARCHAR(254) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    phone          VARCHAR(30),
    logo_url       VARCHAR(300),
    -- Address (embedded value object)
    street         VARCHAR(200),
    city           VARCHAR(100),
    region         VARCHAR(100),
    postal_code    VARCHAR(20),
    country        VARCHAR(100),

    CONSTRAINT pk_owners PRIMARY KEY (id),
    CONSTRAINT uk_owners_email UNIQUE (email)
);
