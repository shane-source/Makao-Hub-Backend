CREATE TABLE bookings
(
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    renter_id             UUID NOT NULL,
    owner_id              UUID NOT NULL,
    listing_id            UUID NOT NULL,
    listing_kind          VARCHAR(24) NOT NULL,
    source_application_id UUID NOT NULL,
    rent_amount_minor     BIGINT NOT NULL,
    deposit_amount_minor  BIGINT NOT NULL,
    total_amount_minor    BIGINT NOT NULL,
    currency              CHAR(3) NOT NULL DEFAULT 'KES',
    status                VARCHAR(24) NOT NULL DEFAULT 'AWAITING_PAYMENT',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT bookings_renter_foreign_key
        FOREIGN KEY (renter_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT bookings_owner_foreign_key
        FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT bookings_different_parties_check
        CHECK (renter_id <> owner_id),
    CONSTRAINT bookings_listing_kind_check
        CHECK (listing_kind IN ('LANDLORD_LISTING', 'SUBLESSOR_LISTING')),
    CONSTRAINT bookings_amounts_check
        CHECK (
            rent_amount_minor > 0
            AND deposit_amount_minor > 0
            AND total_amount_minor = rent_amount_minor + deposit_amount_minor
        ),
    CONSTRAINT bookings_currency_check
        CHECK (currency = 'KES'),
    CONSTRAINT bookings_status_check
        CHECK (status IN (
            'AWAITING_PAYMENT', 'PAYMENT_PENDING', 'PAID_HELD',
            'ACTIVE', 'CANCELLED'
        )),
    CONSTRAINT bookings_source_application_unique
        UNIQUE (listing_kind, source_application_id)
);

CREATE INDEX bookings_renter_created_index
    ON bookings (renter_id, created_at DESC, id DESC);
CREATE INDEX bookings_owner_created_index
    ON bookings (owner_id, created_at DESC, id DESC);
CREATE INDEX bookings_status_created_index
    ON bookings (status, created_at ASC, id ASC);
