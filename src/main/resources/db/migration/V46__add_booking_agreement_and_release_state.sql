ALTER TABLE bookings
    ADD COLUMN renter_agreed_at TIMESTAMPTZ,
    ADD COLUMN owner_agreed_at TIMESTAMPTZ,
    ADD COLUMN released_at TIMESTAMPTZ,
    ADD COLUMN payout_reference VARCHAR(120),
    ADD COLUMN release_idempotency_key VARCHAR(100);

ALTER TABLE bookings
    ADD CONSTRAINT bookings_release_state_check
    CHECK (
        released_at IS NULL
        OR (
            status = 'ACTIVE'
            AND renter_agreed_at IS NOT NULL
            AND owner_agreed_at IS NOT NULL
            AND payout_reference IS NOT NULL
            AND release_idempotency_key IS NOT NULL
        )
    );

ALTER TABLE ledger_entries
    DROP CONSTRAINT ledger_account_type_check;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_account_type_check
    CHECK (account_type IN (
        'RENTER_CLEARING', 'ESCROW_HOLD', 'PLATFORM_REVENUE',
        'OWNER_PAYABLE'
    ));

ALTER TABLE ledger_entries
    DROP CONSTRAINT ledger_payment_line_unique;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_transaction_line_unique
    UNIQUE (transaction_id, component, side);

ALTER TABLE payment_audit_events
    DROP CONSTRAINT payment_audit_event_check;

ALTER TABLE payment_audit_events
    ADD CONSTRAINT payment_audit_event_check
    CHECK (event_type IN (
        'INTENT_CREATED', 'PROVIDER_SUCCEEDED', 'PROVIDER_FAILED',
        'BOOKING_PAID_HELD', 'LEDGER_POSTED', 'RENTER_AGREEMENT_CONFIRMED',
        'OWNER_AGREEMENT_CONFIRMED', 'ESCROW_RELEASED'
    ));
