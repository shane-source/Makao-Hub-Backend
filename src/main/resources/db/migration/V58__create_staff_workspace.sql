-- Owner decision: all existing published listings are hidden until staff approval.
-- Keep listing, application, entitlement and payment records; do not delete them.
CREATE TABLE staff_accounts (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    staff_role VARCHAR(30) NOT NULL CHECK (staff_role IN ('STAFF_LEAD','VERIFICATION_REVIEWER','SAFETY_MODERATOR','PAYMENT_REVIEWER')),
    can_view_documents BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (NOT can_view_documents OR staff_role = 'VERIFICATION_REVIEWER')
);
CREATE TABLE staff_invitations (
    id UUID PRIMARY KEY,
    email_digest VARCHAR(64) NOT NULL,
    token_digest VARCHAR(64) UNIQUE NOT NULL,
    staff_role VARCHAR(30) NOT NULL CHECK (staff_role IN ('STAFF_LEAD','VERIFICATION_REVIEWER','SAFETY_MODERATOR','PAYMENT_REVIEWER')),
    can_view_documents BOOLEAN NOT NULL,
    invited_by UUID REFERENCES staff_accounts(user_id),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    accepted_at TIMESTAMPTZ,
    CHECK (expires_at > issued_at),
    CHECK (NOT can_view_documents OR staff_role = 'VERIFICATION_REVIEWER')
);
CREATE TABLE staff_login_challenges (
    challenge_id UUID PRIMARY KEY REFERENCES auth_otp_challenges(id),
    user_id UUID NOT NULL REFERENCES staff_accounts(user_id)
);
CREATE TABLE staff_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES staff_accounts(user_id),
    token_family_id UUID UNIQUE NOT NULL,
    mfa_verified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK (expires_at > mfa_verified_at)
);
CREATE TABLE staff_access_proofs (
    token_digest VARCHAR(64) PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES staff_sessions(id),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX staff_sessions_user_idx ON staff_sessions(user_id);
CREATE INDEX staff_access_proofs_session_idx ON staff_access_proofs(session_id);

CREATE TABLE admin_audit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id UUID REFERENCES users(id),
    action VARCHAR(60) NOT NULL,
    subject_id UUID,
    reason VARCHAR(500) NOT NULL,
    previous_state VARCHAR(60),
    new_state VARCHAR(60),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX admin_audit_actor_time_idx ON admin_audit_events(actor_id, occurred_at DESC, id DESC);
CREATE INDEX admin_audit_subject_time_idx ON admin_audit_events(subject_id, occurred_at DESC, id DESC);
CREATE FUNCTION reject_admin_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Admin audit records are append-only'; END;
$$;
CREATE TRIGGER admin_audit_append_only BEFORE UPDATE OR DELETE ON admin_audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_admin_audit_mutation();
CREATE TRIGGER admin_audit_no_truncate BEFORE TRUNCATE ON admin_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_admin_audit_mutation();

CREATE TABLE admin_commands (
    actor_id UUID NOT NULL REFERENCES staff_accounts(user_id),
    command_key UUID NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(actor_id, command_key)
);
CREATE TABLE verification_assignments (
    user_id UUID NOT NULL,
    role VARCHAR(40) NOT NULL,
    reviewer_id UUID NOT NULL REFERENCES staff_accounts(user_id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(user_id, role),
    FOREIGN KEY(user_id, role) REFERENCES role_verifications(user_id, role)
);
CREATE TABLE verification_document_reviews (
    document_id UUID NOT NULL,
    role VARCHAR(40) NOT NULL CHECK (role IN ('RENTER','LANDLORD','SUBLESSOR')),
    reviewer_id UUID NOT NULL REFERENCES staff_accounts(user_id),
    evidence_updated_at TIMESTAMPTZ NOT NULL,
    viewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMPTZ,
    PRIMARY KEY(document_id, role, reviewer_id)
);

ALTER TABLE listings
    ADD COLUMN moderation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (moderation_status IN ('PENDING','APPROVED','REJECTED')),
    ADD COLUMN moderation_reason VARCHAR(500),
    ADD COLUMN moderation_submitted_at TIMESTAMPTZ,
    ADD COLUMN moderation_reviewed_at TIMESTAMPTZ,
    ADD COLUMN moderation_reviewed_by UUID REFERENCES staff_accounts(user_id),
    ADD COLUMN restore_publication_after_review BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sublessor_listings
    ADD COLUMN moderation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (moderation_status IN ('PENDING','APPROVED','REJECTED')),
    ADD COLUMN moderation_reason VARCHAR(500),
    ADD COLUMN moderation_submitted_at TIMESTAMPTZ,
    ADD COLUMN moderation_reviewed_at TIMESTAMPTZ,
    ADD COLUMN moderation_reviewed_by UUID REFERENCES staff_accounts(user_id),
    ADD COLUMN restore_publication_after_review BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO admin_audit_events(action, subject_id, reason, previous_state, new_state)
SELECT 'LISTING_APPROVAL_GATE_ENABLED', id, 'Owner requested hiding existing listings until approval', status, 'PAUSED_PENDING_REVIEW'
FROM listings WHERE status = 'PUBLISHED';
INSERT INTO admin_audit_events(action, subject_id, reason, previous_state, new_state)
SELECT 'SHARED_LISTING_APPROVAL_GATE_ENABLED', id, 'Owner requested hiding existing listings until approval', status, 'PAUSED_PENDING_REVIEW'
FROM sublessor_listings WHERE status = 'PUBLISHED';
UPDATE listings SET status = 'PAUSED', restore_publication_after_review = TRUE,
    moderation_submitted_at = CURRENT_TIMESTAMP WHERE status = 'PUBLISHED';
UPDATE sublessor_listings SET status = 'PAUSED', restore_publication_after_review = TRUE,
    moderation_submitted_at = CURRENT_TIMESTAMP WHERE status = 'PUBLISHED';

CREATE FUNCTION enforce_listing_approval() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND
       (to_jsonb(NEW) - ARRAY['status','updated_at','moderation_status','moderation_reason','moderation_submitted_at',
            'moderation_reviewed_at','moderation_reviewed_by','restore_publication_after_review']) IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','updated_at','moderation_status','moderation_reason','moderation_submitted_at',
            'moderation_reviewed_at','moderation_reviewed_by','restore_publication_after_review']) THEN
        NEW.moderation_status := 'PENDING';
        NEW.moderation_submitted_at := NULL;
        NEW.moderation_reviewed_at := NULL;
        NEW.moderation_reviewed_by := NULL;
        NEW.restore_publication_after_review := FALSE;
    END IF;
    IF NEW.status = 'PUBLISHED' AND NEW.moderation_status <> 'APPROVED' THEN
        RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Listing approval is required before publication';
    END IF;
    IF NEW.status = 'PUBLISHED' THEN
        IF TG_TABLE_NAME = 'listings' THEN
            IF NOT EXISTS(SELECT 1 FROM role_verifications v JOIN users u ON u.id=v.user_id
                WHERE v.user_id=NEW.landlord_id AND v.role='LANDLORD' AND v.status='VERIFIED' AND u.account_status='ACTIVE') THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Current landlord verification required';
            END IF;
        ELSE
            IF NOT EXISTS(SELECT 1 FROM role_verifications v JOIN users u ON u.id=v.user_id
                WHERE v.user_id=NEW.sublessor_id AND v.role='SUBLESSOR' AND v.status='VERIFIED' AND u.account_status='ACTIVE') THEN
                RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Current sublessor verification required';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER listings_require_approval BEFORE INSERT OR UPDATE ON listings
    FOR EACH ROW EXECUTE FUNCTION enforce_listing_approval();
CREATE TRIGGER shared_listings_require_approval BEFORE INSERT OR UPDATE ON sublessor_listings
    FOR EACH ROW EXECUTE FUNCTION enforce_listing_approval();
CREATE INDEX listings_moderation_queue_idx ON listings(moderation_status, moderation_submitted_at, id);
CREATE INDEX shared_moderation_queue_idx ON sublessor_listings(moderation_status, moderation_submitted_at, id);

CREATE FUNCTION pause_listings_when_role_unverified() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status <> 'VERIFIED' THEN
        IF NEW.role='LANDLORD' THEN
            UPDATE listings SET status='PAUSED',restore_publication_after_review=FALSE WHERE landlord_id=NEW.user_id AND status='PUBLISHED';
        ELSIF NEW.role='SUBLESSOR' THEN
            UPDATE sublessor_listings SET status='PAUSED',restore_publication_after_review=FALSE WHERE sublessor_id=NEW.user_id AND status='PUBLISHED';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER role_verification_publication_gate AFTER UPDATE OF status ON role_verifications
    FOR EACH ROW EXECUTE FUNCTION pause_listings_when_role_unverified();

CREATE FUNCTION invalidate_listing_evidence_approval() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE target UUID; target_table TEXT;
BEGIN
    IF TG_TABLE_NAME IN ('listing_media','listing_locations') THEN
        target := CASE WHEN TG_OP = 'DELETE' THEN OLD.listing_id ELSE NEW.listing_id END;
        target_table := 'listings';
    ELSIF TG_TABLE_NAME = 'sublessor_household_details' THEN
        target := CASE WHEN TG_OP = 'DELETE' THEN OLD.listing_id ELSE NEW.listing_id END;
        target_table := 'sublessor_listings';
    ELSE
        target := CASE WHEN TG_OP = 'DELETE' THEN OLD.sublessor_listing_id ELSE NEW.sublessor_listing_id END;
        target_table := 'sublessor_listings';
    END IF;
    EXECUTE format('UPDATE %I SET moderation_status = ''PENDING'', moderation_submitted_at = NULL,
        moderation_reviewed_at = NULL, moderation_reviewed_by = NULL, restore_publication_after_review = FALSE,
        status = CASE WHEN status = ''PUBLISHED'' THEN ''PAUSED'' ELSE status END WHERE id = $1', target_table) USING target;
    RETURN NULL;
END;
$$;
CREATE TRIGGER listing_media_invalidates_review AFTER INSERT OR UPDATE OR DELETE ON listing_media
    FOR EACH ROW EXECUTE FUNCTION invalidate_listing_evidence_approval();
CREATE TRIGGER listing_location_invalidates_review AFTER INSERT OR UPDATE OR DELETE ON listing_locations
    FOR EACH ROW EXECUTE FUNCTION invalidate_listing_evidence_approval();
CREATE TRIGGER shared_media_invalidates_review AFTER INSERT OR UPDATE OR DELETE ON sublessor_listing_media
    FOR EACH ROW EXECUTE FUNCTION invalidate_listing_evidence_approval();
CREATE TRIGGER shared_location_invalidates_review AFTER INSERT OR UPDATE OR DELETE ON sublessor_listing_locations
    FOR EACH ROW EXECUTE FUNCTION invalidate_listing_evidence_approval();
CREATE TRIGGER household_invalidates_review AFTER INSERT OR UPDATE OR DELETE ON sublessor_household_details
    FOR EACH ROW EXECUTE FUNCTION invalidate_listing_evidence_approval();

CREATE TABLE admin_message_reviews (
    message_id UUID NOT NULL,
    conversation_kind VARCHAR(20) NOT NULL CHECK (conversation_kind IN ('RENTAL','ROOMMATE')),
    state VARCHAR(30) NOT NULL CHECK (state IN ('WARNED','ESCALATED')),
    reviewed_by UUID NOT NULL REFERENCES staff_accounts(user_id),
    reason VARCHAR(500) NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(message_id, conversation_kind)
);
CREATE TABLE user_reports (
    id UUID PRIMARY KEY,
    reporter_id UUID NOT NULL REFERENCES users(id),
    subject_kind VARCHAR(20) NOT NULL CHECK (subject_kind IN ('LANDLORD_LISTING','SHARED_LISTING','RENTAL_MESSAGE','ROOMMATE_MESSAGE')),
    subject_id UUID NOT NULL,
    category VARCHAR(30) NOT NULL CHECK (category IN ('MISLEADING_CONTENT','PAYMENT_REQUEST','UNSAFE_CONTENT','OTHER')),
    safe_description VARCHAR(1000) NOT NULL,
    priority VARCHAR(10) NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL','HIGH')),
    state VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','INVESTIGATING','ESCALATED','RESOLVED')),
    assigned_to UUID REFERENCES staff_accounts(user_id),
    resolution_reason VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX user_reports_queue_idx ON user_reports(state, created_at DESC, id);

ALTER TABLE notifications DROP CONSTRAINT IF EXISTS notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (type IN (
    'APPLICATION_SUBMITTED','APPLICATION_UNDER_REVIEW','APPLICATION_APPROVED','APPLICATION_REJECTED',
    'ROOMMATE_INTEREST_SUBMITTED','ROOMMATE_INTEREST_UNDER_REVIEW','ROOMMATE_INTEREST_ACCEPTED','ROOMMATE_INTEREST_DECLINED',
    'VERIFICATION_DECISION','LISTING_REVIEWED','MODERATION_WARNING','REPORT_RESOLVED'));

-- Browser Supabase clients must never use these as an alternative to the staff API.
ALTER TABLE staff_accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_login_challenges ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE staff_access_proofs ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE verification_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE verification_document_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE admin_message_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_reports ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON staff_accounts, staff_invitations, staff_login_challenges, staff_sessions,
    staff_access_proofs, admin_audit_events, admin_commands, verification_assignments,
    verification_document_reviews, admin_message_reviews, user_reports FROM PUBLIC;
