-- Owner move-in report and a fresh renter True/False response are the only move-in confirmations.
-- Existing agreement timestamps remain as history; no booking rows are rewritten.
ALTER TABLE bookings DROP CONSTRAINT bookings_fresh_move_in_response_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_fresh_move_in_response_check CHECK (
    (move_in_accepted IS NULL AND move_in_answered_at IS NULL AND move_in_response_key IS NULL)
    OR (move_in_accepted IS NOT NULL AND move_in_answered_at IS NOT NULL AND move_in_response_key IS NOT NULL
        AND owner_move_in_confirmed_at IS NOT NULL AND move_in_answered_at >= owner_move_in_confirmed_at
        AND ((move_in_accepted AND move_in_confirmed_at IS NOT NULL AND release_eligible_at IS NOT NULL
                AND move_in_confirmed_at = move_in_answered_at
                AND release_eligible_at = move_in_answered_at + INTERVAL '5 minutes')
             OR (NOT move_in_accepted AND release_eligible_at IS NULL)))
);

-- The existing release trigger still requires a fresh True response after the wait.
ALTER TABLE bookings DROP CONSTRAINT bookings_release_state_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_release_state_check CHECK (
    released_at IS NULL
    OR (status = 'ACTIVE' AND payout_reference IS NOT NULL AND release_idempotency_key IS NOT NULL)
);
