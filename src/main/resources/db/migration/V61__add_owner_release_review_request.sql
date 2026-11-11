-- An owner request is only a review signal; it never authorises a payout.
ALTER TABLE bookings ADD COLUMN owner_release_requested_at TIMESTAMPTZ;
ALTER TABLE bookings ADD CONSTRAINT bookings_owner_release_request_after_move_in_check
    CHECK (owner_release_requested_at IS NULL OR
           (move_in_confirmed_at IS NOT NULL AND owner_release_requested_at >= move_in_confirmed_at));

ALTER TABLE payment_audit_events DROP CONSTRAINT payment_audit_event_check;
ALTER TABLE payment_audit_events ADD CONSTRAINT payment_audit_event_check CHECK (event_type IN (
    'INTENT_CREATED', 'PROVIDER_SUCCEEDED', 'PROVIDER_FAILED',
    'BOOKING_PAID_HELD', 'LEDGER_POSTED', 'RENTER_AGREEMENT_CONFIRMED',
    'OWNER_AGREEMENT_CONFIRMED', 'MOVE_IN_CONFIRMED', 'OWNER_RELEASE_REVIEW_REQUESTED',
    'PAYMENT_DISPUTED', 'ESCROW_RELEASED'
));
