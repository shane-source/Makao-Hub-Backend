CREATE TABLE payment_intents
(
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    payer_user_id     UUID        NOT NULL,
    payment_type      VARCHAR(40) NOT NULL,
    listing_id        UUID,
    booking_id        UUID,
    amount_minor      BIGINT      NOT NULL,
    currency          CHAR(3)     NOT NULL DEFAULT 'KES',
    idempotency_key   VARCHAR(100) NOT NULL,
    status            VARCHAR(24) NOT NULL DEFAULT 'CREATED',
    provider_reference VARCHAR(120),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at      TIMESTAMPTZ,
    version           BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT payment_intents_payer_foreign_key
        FOREIGN KEY (payer_user_id) REFERENCES users (id)
        ON DELETE RESTRICT,

    CONSTRAINT payment_intents_amount_check
        CHECK (amount_minor > 0),

    CONSTRAINT payment_intents_currency_check
        CHECK (currency = 'KES'),

    CONSTRAINT payment_intents_status_check
        CHECK (status IN (
            'CREATED', 'PENDING', 'SUCCEEDED', 'FAILED', 'EXPIRED',
            'REFUND_PENDING', 'REFUNDED', 'DISPUTED'
        )),

    CONSTRAINT payment_intents_target_check
        CHECK (
            (payment_type = 'BOOKING'
                AND booking_id IS NOT NULL
                AND listing_id IS NULL)
            OR
            (payment_type IN (
                    'EXACT_LOCATION_UNLOCK', 'VIDEO_UNLOCK',
                    'PHONE_UNLOCK', 'BOOST', 'LISTING_ADD_ON'
                )
                AND listing_id IS NOT NULL
                AND booking_id IS NULL)
            OR
            (payment_type = 'PLAN_UPGRADE'
                AND listing_id IS NULL
                AND booking_id IS NULL)
        ),

    CONSTRAINT payment_intents_type_check
        CHECK (payment_type IN (
            'BOOKING', 'EXACT_LOCATION_UNLOCK', 'VIDEO_UNLOCK',
            'PHONE_UNLOCK', 'PLAN_UPGRADE', 'BOOST', 'LISTING_ADD_ON'
        )),

    CONSTRAINT payment_intents_idempotency_unique
        UNIQUE (payer_user_id, idempotency_key),

    CONSTRAINT payment_intents_provider_reference_unique
        UNIQUE (provider_reference)
);

CREATE INDEX payment_intents_payer_created_index
    ON payment_intents (payer_user_id, created_at DESC, id DESC);

CREATE INDEX payment_intents_status_created_index
    ON payment_intents (status, created_at ASC, id ASC);
