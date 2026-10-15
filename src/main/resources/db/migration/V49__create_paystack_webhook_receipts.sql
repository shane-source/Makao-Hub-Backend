CREATE TABLE paystack_webhook_receipts
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_key          VARCHAR(220) NOT NULL UNIQUE,
    event_type         VARCHAR(80) NOT NULL,
    provider_reference VARCHAR(120) NOT NULL,
    received_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX paystack_webhook_reference_idx
    ON paystack_webhook_receipts (provider_reference, received_at DESC);

ALTER TABLE payment_audit_events
    DROP CONSTRAINT payment_audit_event_check;

ALTER TABLE payment_audit_events
    ADD CONSTRAINT payment_audit_event_check
    CHECK (event_type IN (
        'INTENT_CREATED', 'PROVIDER_SUCCEEDED', 'PROVIDER_FAILED',
        'BOOKING_PAID_HELD', 'LEDGER_POSTED', 'RENTER_AGREEMENT_CONFIRMED',
        'OWNER_AGREEMENT_CONFIRMED', 'MOVE_IN_CONFIRMED', 'PAYMENT_DISPUTED',
        'ESCROW_RELEASED'
    ));
