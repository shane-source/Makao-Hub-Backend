-- Approved TEST policy: five minutes after the second move-in confirmation.
-- Completed releases and legacy bookings awaiting owner confirmation retain their history.
ALTER TABLE bookings DROP CONSTRAINT bookings_move_in_release_check;

WITH deadlines AS (
    SELECT id, release_eligible_at AS previous_deadline,
           GREATEST(move_in_confirmed_at, owner_move_in_confirmed_at) + INTERVAL '5 minutes' AS next_deadline
    FROM bookings
    WHERE status = 'PAID_HELD' AND released_at IS NULL
      AND move_in_confirmed_at IS NOT NULL AND owner_move_in_confirmed_at IS NOT NULL
), changed AS (
    UPDATE bookings b
    SET release_eligible_at = d.next_deadline, updated_at = CURRENT_TIMESTAMP, version = b.version + 1
    FROM deadlines d
    WHERE b.id = d.id AND b.release_eligible_at IS DISTINCT FROM d.next_deadline
    RETURNING b.id, d.previous_deadline, d.next_deadline
)
INSERT INTO admin_audit_events(action, subject_id, reason, previous_state, new_state)
SELECT 'BOOKING_TEST_RELEASE_WINDOW_CHANGED', id,
       'Approved five-minute TEST wait after both move-in confirmations; staff approval remains required',
       previous_deadline::text, next_deadline::text
FROM changed;

-- NOT VALID preserves completed historical rows; every new insert/update must obey this rule.
ALTER TABLE bookings ADD CONSTRAINT bookings_move_in_release_check CHECK (
    (
        owner_move_in_confirmed_at IS NULL
        AND (
            release_eligible_at IS NULL
            -- Compatibility for an old renter-only deadline; owner confirmation replaces it.
            OR (released_at IS NULL AND move_in_confirmed_at IS NOT NULL
                AND release_eligible_at = move_in_confirmed_at + INTERVAL '24 hours')
        )
    )
    OR (
        move_in_confirmed_at IS NOT NULL AND owner_move_in_confirmed_at IS NOT NULL
        AND renter_agreed_at IS NOT NULL AND owner_agreed_at IS NOT NULL
        AND release_eligible_at IS NOT NULL
        AND release_eligible_at = GREATEST(move_in_confirmed_at, owner_move_in_confirmed_at) + INTERVAL '5 minutes'
    )
) NOT VALID;

-- Enforce timing on new releases without rewriting already completed historical records.
ALTER TABLE bookings ADD CONSTRAINT bookings_release_after_deadline_check
    CHECK (released_at IS NULL OR released_at >= release_eligible_at) NOT VALID;
