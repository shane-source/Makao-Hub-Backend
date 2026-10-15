CREATE TABLE listing_unlock_entitlements
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_intent_id  UUID NOT NULL UNIQUE,
    payer_user_id      UUID NOT NULL,
    listing_id         UUID NOT NULL,
    unlock_type        VARCHAR(40) NOT NULL,
    amount_minor       BIGINT NOT NULL,
    currency           CHAR(3) NOT NULL DEFAULT 'KES',
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at         TIMESTAMPTZ,
    CONSTRAINT listing_unlock_payment_fk FOREIGN KEY (payment_intent_id)
        REFERENCES payment_intents (id) ON DELETE RESTRICT,
    CONSTRAINT listing_unlock_payer_fk FOREIGN KEY (payer_user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT listing_unlock_amount_check CHECK (amount_minor > 0),
    CONSTRAINT listing_unlock_currency_check CHECK (currency = 'KES'),
    CONSTRAINT listing_unlock_type_check CHECK (unlock_type IN
        ('EXACT_LOCATION_UNLOCK', 'VIDEO_UNLOCK', 'PHONE_UNLOCK')),
    CONSTRAINT listing_unlock_status_check CHECK (status IN
        ('ACTIVE', 'REVOKED', 'REFUNDED'))
);

CREATE INDEX listing_unlock_lookup_index ON listing_unlock_entitlements
    (payer_user_id, listing_id, unlock_type, status);
