-- catalog module baseline. Runs with search_path = catalog (see FlywayModulesConfig).

CREATE TABLE categories (
    id          UUID         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,

    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT uk_categories_name UNIQUE (name)
);

CREATE TABLE items (
    id               UUID          NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL,
    version          BIGINT        NOT NULL,
    name             VARCHAR(150)  NOT NULL,
    description      VARCHAR(2000),
    price            NUMERIC(10,2) NOT NULL,
    category_id      UUID          NOT NULL,
    available        BOOLEAN       NOT NULL DEFAULT TRUE,
    lead_time_hours  INTEGER       NOT NULL DEFAULT 0,
    -- NULL means stock is not tracked for this item.
    stock_quantity   INTEGER,

    CONSTRAINT pk_items PRIMARY KEY (id),
    -- Same module, so a real FK is fine (only cross-module references are plain ids).
    CONSTRAINT fk_items_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT ck_items_price_positive CHECK (price > 0),
    CONSTRAINT ck_items_lead_time_non_negative CHECK (lead_time_hours >= 0),
    CONSTRAINT ck_items_stock_non_negative CHECK (stock_quantity >= 0)
);

CREATE INDEX idx_items_category_id ON items (category_id);
CREATE INDEX idx_items_available ON items (available);

CREATE TABLE item_images (
    item_id    UUID         NOT NULL,
    position   INTEGER      NOT NULL,
    image_url  VARCHAR(255) NOT NULL,

    CONSTRAINT pk_item_images PRIMARY KEY (item_id, position),
    CONSTRAINT fk_item_images_item FOREIGN KEY (item_id) REFERENCES items (id) ON DELETE CASCADE,
    CONSTRAINT ck_item_images_position_non_negative CHECK (position >= 0)
);
