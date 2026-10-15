ALTER TABLE bookings
    ADD COLUMN move_in_confirmed_at TIMESTAMPTZ,
    ADD COLUMN release_eligible_at TIMESTAMPTZ;

ALTER TABLE bookings
    ADD CONSTRAINT bookings_move_in_release_check
    CHECK (
        (move_in_confirmed_at IS NULL AND release_eligible_at IS NULL)
        OR (
            move_in_confirmed_at IS NOT NULL
            AND release_eligible_at = move_in_confirmed_at + INTERVAL '24 hours'
        )
    );

ALTER TABLE bookings
    ADD CONSTRAINT bookings_release_requires_move_in_check
    CHECK (
        released_at IS NULL
        OR (
            move_in_confirmed_at IS NOT NULL
            AND release_eligible_at IS NOT NULL
        )
    );

CREATE INDEX bookings_release_eligibility_idx
    ON bookings (status, release_eligible_at);

ALTER TABLE payment_audit_events
    DROP CONSTRAINT payment_audit_event_check;

ALTER TABLE payment_audit_events
    ADD CONSTRAINT payment_audit_event_check
    CHECK (event_type IN (
        'INTENT_CREATED', 'PROVIDER_SUCCEEDED', 'PROVIDER_FAILED',
        'BOOKING_PAID_HELD', 'LEDGER_POSTED', 'RENTER_AGREEMENT_CONFIRMED',
        'OWNER_AGREEMENT_CONFIRMED', 'MOVE_IN_CONFIRMED', 'ESCROW_RELEASED'
    ));
