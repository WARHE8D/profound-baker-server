-- ordering module baseline. Runs with search_path = ordering (see FlywayModulesConfig).
-- item_id and owner_id point at other modules (catalog, identity): plain UUIDs with
-- no FK constraint, so this schema can move to its own database later.

CREATE TABLE working_hours (
    id                       UUID        NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    version                  BIGINT      NOT NULL,
    owner_id                 UUID        NOT NULL,
    day_of_week              VARCHAR(10) NOT NULL,
    start_time               TIME(0)     NOT NULL,
    end_time                 TIME(0)     NOT NULL,
    slot_duration_minutes    INTEGER     NOT NULL,
    max_concurrent_bookings  INTEGER     NOT NULL,

    CONSTRAINT pk_working_hours PRIMARY KEY (id),
    CONSTRAINT uk_working_hours_owner_day UNIQUE (owner_id, day_of_week),
    CONSTRAINT ck_working_hours_day_of_week CHECK (day_of_week IN
        ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT ck_working_hours_window CHECK (end_time > start_time),
    CONSTRAINT ck_working_hours_slot_duration CHECK (slot_duration_minutes >= 5),
    CONSTRAINT ck_working_hours_capacity CHECK (max_concurrent_bookings >= 1)
);

CREATE INDEX idx_working_hours_owner ON working_hours (owner_id);

CREATE TABLE blocked_dates (
    id                  UUID         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    version             BIGINT       NOT NULL,
    owner_id            UUID         NOT NULL,
    start_date          DATE         NOT NULL,
    end_date            DATE         NOT NULL,
    reason              VARCHAR(200),
    recurring_annually  BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_blocked_dates PRIMARY KEY (id),
    CONSTRAINT ck_blocked_dates_range CHECK (end_date >= start_date)
);

CREATE INDEX idx_blocked_dates_owner ON blocked_dates (owner_id);
CREATE INDEX idx_blocked_dates_range ON blocked_dates (start_date, end_date);

CREATE TABLE bookings (
    id                   UUID          NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL,
    updated_at           TIMESTAMPTZ   NOT NULL,
    version              BIGINT        NOT NULL,
    item_id              UUID          NOT NULL,
    quantity             INTEGER       NOT NULL,
    scheduled_for        TIMESTAMPTZ   NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    -- GuestContact (embedded value object)
    guest_name           VARCHAR(150)  NOT NULL,
    guest_email          VARCHAR(254)  NOT NULL,
    guest_phone          VARCHAR(30)   NOT NULL,
    notes                VARCHAR(1000),
    cancellation_reason  VARCHAR(300),

    CONSTRAINT pk_bookings PRIMARY KEY (id),
    CONSTRAINT ck_bookings_quantity CHECK (quantity >= 1),
    CONSTRAINT ck_bookings_status CHECK (status IN
        ('PENDING', 'CONFIRMED', 'IN_PROGRESS', 'FULFILLED', 'CANCELLED'))
);

CREATE INDEX idx_bookings_scheduled_for ON bookings (scheduled_for);
CREATE INDEX idx_bookings_status ON bookings (status);
CREATE INDEX idx_bookings_item ON bookings (item_id);

CREATE TABLE invoices (
    id                 UUID          NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    version            BIGINT        NOT NULL,
    booking_id         UUID          NOT NULL,
    subtotal           NUMERIC(10,2) NOT NULL,
    discount_amount    NUMERIC(10,2) NOT NULL DEFAULT 0,
    status             VARCHAR(20)   NOT NULL,
    payment_method     VARCHAR(20),
    payment_reference  VARCHAR(200),
    paid_at            TIMESTAMPTZ,

    CONSTRAINT pk_invoices PRIMARY KEY (id),
    CONSTRAINT uk_invoices_booking UNIQUE (booking_id),
    CONSTRAINT fk_invoices_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT ck_invoices_amounts CHECK (subtotal >= 0 AND discount_amount >= 0),
    CONSTRAINT ck_invoices_status CHECK (status IN ('PENDING', 'PAID', 'REFUNDED', 'VOID')),
    CONSTRAINT ck_invoices_payment_method CHECK (payment_method IN
        ('ONLINE_CARD', 'CASH', 'E_TRANSFER', 'OTHER'))
);

CREATE INDEX idx_invoices_status ON invoices (status);
