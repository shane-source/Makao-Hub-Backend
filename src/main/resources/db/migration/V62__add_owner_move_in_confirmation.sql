-- Owner move-in acknowledgement is separate from the renter-triggered 24-hour hold.
-- Existing rows remain untouched; outstanding bookings require an owner confirmation before release.
ALTER TABLE bookings ADD COLUMN owner_move_in_confirmed_at TIMESTAMPTZ;
ALTER TABLE bookings ADD CONSTRAINT bookings_owner_move_in_after_renter_check
    CHECK (owner_move_in_confirmed_at IS NULL OR
           (move_in_confirmed_at IS NOT NULL AND owner_move_in_confirmed_at >= move_in_confirmed_at));
-- Existing review/release rows predate this rule. Enforce it on new writes without rewriting history.
ALTER TABLE bookings ADD CONSTRAINT bookings_review_requires_owner_move_in_check
    CHECK (owner_release_requested_at IS NULL OR owner_move_in_confirmed_at IS NOT NULL) NOT VALID;
ALTER TABLE bookings ADD CONSTRAINT bookings_release_requires_owner_move_in_check
    CHECK (released_at IS NULL OR owner_move_in_confirmed_at IS NOT NULL) NOT VALID;

ALTER TABLE payment_audit_events DROP CONSTRAINT payment_audit_event_check;
ALTER TABLE payment_audit_events ADD CONSTRAINT payment_audit_event_check CHECK (event_type IN (
    'INTENT_CREATED', 'PROVIDER_SUCCEEDED', 'PROVIDER_FAILED',
    'BOOKING_PAID_HELD', 'LEDGER_POSTED', 'RENTER_AGREEMENT_CONFIRMED',
    'OWNER_AGREEMENT_CONFIRMED', 'MOVE_IN_CONFIRMED', 'OWNER_MOVE_IN_CONFIRMED',
    'OWNER_RELEASE_REVIEW_REQUESTED', 'PAYMENT_DISPUTED', 'ESCROW_RELEASED'
));
