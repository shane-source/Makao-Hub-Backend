-- Approved TEST policy: owner reports first; a fresh renter True starts five minutes.
-- Existing confirmations and completed releases are not rewritten or treated as fresh consent.
ALTER TABLE bookings
    ADD COLUMN move_in_accepted BOOLEAN,
    ADD COLUMN move_in_answered_at TIMESTAMPTZ,
    ADD COLUMN move_in_response_key VARCHAR(100),
    ADD COLUMN test_payout_status VARCHAR(32) NOT NULL DEFAULT 'NOT_QUEUED';
ALTER TABLE bookings DROP CONSTRAINT bookings_owner_move_in_after_renter_check;
ALTER TABLE bookings DROP CONSTRAINT bookings_move_in_release_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_fresh_move_in_response_check CHECK (
    (move_in_accepted IS NULL AND move_in_answered_at IS NULL AND move_in_response_key IS NULL)
    OR (move_in_accepted IS NOT NULL AND move_in_answered_at IS NOT NULL AND move_in_response_key IS NOT NULL
        AND owner_move_in_confirmed_at IS NOT NULL AND move_in_answered_at >= owner_move_in_confirmed_at
        AND renter_agreed_at IS NOT NULL AND owner_agreed_at IS NOT NULL
        AND ((move_in_accepted AND move_in_confirmed_at IS NOT NULL AND release_eligible_at IS NOT NULL
                AND move_in_confirmed_at = move_in_answered_at
                AND release_eligible_at = move_in_answered_at + INTERVAL '5 minutes')
             OR (NOT move_in_accepted AND release_eligible_at IS NULL)))
);
-- Old ACTIVE history remains valid; new releases must have fresh True, enforced by a trigger.
CREATE FUNCTION protect_fresh_move_in_release() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.move_in_accepted IS NOT NULL AND
       (NEW.move_in_accepted IS DISTINCT FROM OLD.move_in_accepted OR
        NEW.move_in_answered_at IS DISTINCT FROM OLD.move_in_answered_at OR
        NEW.move_in_response_key IS DISTINCT FROM OLD.move_in_response_key) THEN
        RAISE EXCEPTION 'Recorded move-in response is immutable';
    END IF;
    IF NEW.released_at IS NOT NULL AND OLD.released_at IS NULL AND
       (NEW.move_in_accepted IS DISTINCT FROM TRUE OR NEW.release_eligible_at IS NULL
        OR NEW.released_at < NEW.release_eligible_at) THEN
        RAISE EXCEPTION 'Fresh renter True and five-minute wait are required';
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER bookings_fresh_move_in_release BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION protect_fresh_move_in_release();

ALTER TABLE notifications ADD COLUMN booking_id UUID REFERENCES bookings(id);
CREATE UNIQUE INDEX notification_booking_type_once ON notifications(user_id,booking_id,type)
    WHERE booking_id IS NOT NULL;
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (
    'APPLICATION_SUBMITTED','APPLICATION_UNDER_REVIEW','APPLICATION_APPROVED','APPLICATION_REJECTED',
    'ROOMMATE_INTEREST_SUBMITTED','ROOMMATE_INTEREST_UNDER_REVIEW','ROOMMATE_INTEREST_ACCEPTED','ROOMMATE_INTEREST_DECLINED',
    'VERIFICATION_DECISION','LISTING_REVIEWED','MODERATION_WARNING','REPORT_RESOLVED',
    'MOVE_IN_REPORTED','MOVE_IN_RESPONSE','TEST_PAYOUT_STATUS'));
-- Approved existing held bookings need a fresh answer, never a silent legacy opt-in.
INSERT INTO notifications(id,user_id,booking_id,type,title,body,created_at)
SELECT gen_random_uuid(),b.renter_id,b.id,'MOVE_IN_REPORTED','Did you move in?',
       owner.full_name || ' previously reported move-in. A fresh True or False response is now required. No response keeps payment held.',
       CURRENT_TIMESTAMP
FROM bookings b JOIN users owner ON owner.id=b.owner_id
WHERE b.status='PAID_HELD' AND b.owner_move_in_confirmed_at IS NOT NULL AND b.released_at IS NULL
ON CONFLICT(user_id,booking_id,type) WHERE booking_id IS NOT NULL DO NOTHING;
ALTER TABLE payment_audit_events DROP CONSTRAINT payment_audit_event_check;
ALTER TABLE payment_audit_events ADD CONSTRAINT payment_audit_event_check CHECK (event_type IN (
    'INTENT_CREATED','PROVIDER_SUCCEEDED','PROVIDER_FAILED','BOOKING_PAID_HELD','LEDGER_POSTED',
    'RENTER_AGREEMENT_CONFIRMED','OWNER_AGREEMENT_CONFIRMED','MOVE_IN_CONFIRMED','OWNER_MOVE_IN_CONFIRMED',
    'OWNER_RELEASE_REVIEW_REQUESTED','PAYMENT_DISPUTED','ESCROW_RELEASED','MOVE_IN_ACCEPTED','MOVE_IN_REJECTED'));
