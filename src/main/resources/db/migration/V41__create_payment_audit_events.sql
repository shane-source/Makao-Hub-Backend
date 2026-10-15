CREATE TABLE payment_audit_events
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_intent_id  UUID NOT NULL,
    actor_user_id      UUID NOT NULL,
    booking_id         UUID,
    payment_type       VARCHAR(40) NOT NULL,
    event_type         VARCHAR(32) NOT NULL,
    amount_minor       BIGINT NOT NULL,
    currency           CHAR(3) NOT NULL DEFAULT 'KES',
    occurred_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT payment_audit_intent_fk
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intents (id)
        ON DELETE RESTRICT,
    CONSTRAINT payment_audit_actor_fk
        FOREIGN KEY (actor_user_id) REFERENCES users (id)
        ON DELETE RESTRICT,
    CONSTRAINT payment_audit_booking_fk
        FOREIGN KEY (booking_id) REFERENCES bookings (id)
        ON DELETE RESTRICT,
    CONSTRAINT payment_audit_amount_check CHECK (amount_minor > 0),
    CONSTRAINT payment_audit_currency_check CHECK (currency = 'KES'),
    CONSTRAINT payment_audit_type_check CHECK (
        payment_type IN (
            'BOOKING', 'EXACT_LOCATION_UNLOCK', 'VIDEO_UNLOCK',
            'PHONE_UNLOCK', 'PLAN_UPGRADE', 'BOOST', 'LISTING_ADD_ON'
        )
    ),
    CONSTRAINT payment_audit_event_check CHECK (
        event_type IN (
            'INTENT_CREATED', 'PROVIDER_SUCCEEDED', 'PROVIDER_FAILED',
            'BOOKING_PAID_HELD', 'LEDGER_POSTED'
        )
    ),
    CONSTRAINT payment_audit_event_unique
        UNIQUE (payment_intent_id, event_type)
);

CREATE INDEX payment_audit_actor_time_index
    ON payment_audit_events (actor_user_id, occurred_at ASC, id ASC);

CREATE INDEX payment_audit_booking_time_index
    ON payment_audit_events (booking_id, occurred_at ASC, id ASC);

CREATE OR REPLACE FUNCTION reject_payment_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'payment_audit_events is append-only';
END;
$$;

CREATE TRIGGER payment_audit_events_append_only
    BEFORE UPDATE OR DELETE ON payment_audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_payment_audit_mutation();
