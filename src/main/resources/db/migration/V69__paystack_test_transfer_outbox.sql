-- Opaque TEST-provider IDs only; no bank numbers, mobile numbers or provider bodies.
CREATE TABLE paystack_test_destinations (
    owner_id UUID PRIMARY KEY REFERENCES users(id),
    owner_role VARCHAR(24) NOT NULL CHECK (owner_role IN ('LANDLORD','SUBLESSOR')),
    recipient_id BIGINT NOT NULL CHECK (recipient_id>0),
    recipient_fingerprint VARCHAR(64) NOT NULL,
    configured_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TRIGGER test_destination_immutable BEFORE UPDATE OR DELETE ON paystack_test_destinations
    FOR EACH ROW EXECUTE FUNCTION reject_admin_audit_mutation();
CREATE TABLE paystack_test_transfers (
    booking_id UUID PRIMARY KEY REFERENCES bookings(id),
    payment_intent_id UUID NOT NULL UNIQUE REFERENCES payment_intents(id),
    owner_id UUID NOT NULL REFERENCES paystack_test_destinations(owner_id),
    recipient_id BIGINT NOT NULL,
    recipient_fingerprint VARCHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
    currency CHAR(3) NOT NULL DEFAULT 'KES' CHECK(currency='KES'),
    reference VARCHAR(50) NOT NULL UNIQUE CHECK(reference ~ '^mh-owner-[a-f0-9-]{36}$'),
    state VARCHAR(20) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','UNKNOWN','PENDING','OTP','SUCCEEDED','FAILED','REVERSED','REVIEW')),
    attempted_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    lease_token UUID,
    next_check_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX test_transfer_work ON paystack_test_transfers(next_check_at,state);
CREATE FUNCTION protect_test_transfer_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='INSERT' THEN
    IF NOT EXISTS (SELECT 1 FROM bookings b JOIN payment_intents p ON p.id=NEW.payment_intent_id
        JOIN paystack_test_destinations d ON d.owner_id=b.owner_id
        WHERE b.id=NEW.booking_id AND b.owner_id=NEW.owner_id AND b.status='PAID_HELD'
          AND b.move_in_accepted IS TRUE AND b.release_eligible_at<=CURRENT_TIMESTAMP
          AND NEW.amount_minor=b.rent_amount_minor+b.deposit_amount_minor
          AND p.booking_id=b.id AND p.status='SUCCEEDED' AND p.payment_type='BOOKING'
          AND p.amount_minor=b.total_amount_minor AND p.currency='KES'
          AND d.recipient_id=NEW.recipient_id AND d.recipient_fingerprint=NEW.recipient_fingerprint) THEN
        RAISE EXCEPTION 'Test instruction does not match an eligible funded booking';
    END IF;
 ELSE
    IF (NEW.booking_id,NEW.payment_intent_id,NEW.owner_id,NEW.recipient_id,NEW.recipient_fingerprint,NEW.amount_minor,NEW.currency,NEW.reference)
        IS DISTINCT FROM (OLD.booking_id,OLD.payment_intent_id,OLD.owner_id,OLD.recipient_id,OLD.recipient_fingerprint,OLD.amount_minor,OLD.currency,OLD.reference) THEN
        RAISE EXCEPTION 'Frozen test instruction cannot change';
    END IF;
    IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
        (OLD.state='QUEUED' AND NEW.state IN ('UNKNOWN','REVIEW')) OR
        (OLD.state IN ('UNKNOWN','PENDING','OTP') AND NEW.state IN ('UNKNOWN','PENDING','OTP','SUCCEEDED','FAILED','REVIEW')) OR
        (OLD.state='SUCCEEDED' AND NEW.state IN ('REVERSED','REVIEW'))
    ) THEN RAISE EXCEPTION 'Illegal test transfer transition'; END IF;
    IF OLD.attempted_at IS NOT NULL AND NEW.attempted_at IS DISTINCT FROM OLD.attempted_at THEN
        RAISE EXCEPTION 'Transfer attempt history cannot change';
    END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER test_transfer_transition BEFORE INSERT OR UPDATE ON paystack_test_transfers
    FOR EACH ROW EXECUTE FUNCTION protect_test_transfer_transition();
CREATE TRIGGER test_transfer_no_delete BEFORE DELETE ON paystack_test_transfers
    FOR EACH ROW EXECUTE FUNCTION reject_admin_audit_mutation();
ALTER TABLE paystack_test_destinations ENABLE ROW LEVEL SECURITY;
ALTER TABLE paystack_test_transfers ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON paystack_test_destinations,paystack_test_transfers FROM PUBLIC;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
    REVOKE ALL ON paystack_test_destinations,paystack_test_transfers FROM anon;
 END IF;
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
    REVOKE ALL ON paystack_test_destinations,paystack_test_transfers FROM authenticated;
 END IF;
END $$;
ALTER TABLE ledger_entries DROP CONSTRAINT ledger_account_type_check;
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_account_type_check CHECK(account_type IN (
 'RENTER_CLEARING','ESCROW_HOLD','PLATFORM_REVENUE','OWNER_PAYABLE','OWNER_PAYOUT'));
