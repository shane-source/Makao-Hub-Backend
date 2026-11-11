-- Distinguish reversible self-deactivation from staff suspension and deletion review.
ALTER TABLE users DROP CONSTRAINT users_account_status_check;
ALTER TABLE users ADD CONSTRAINT users_account_status_check
    CHECK (account_status IN ('ACTIVE','SUSPENDED','DELETED','DEACTIVATED','DELETION_PENDING'));

ALTER TABLE auth_otp_challenges DROP CONSTRAINT auth_otp_challenges_purpose_check;
ALTER TABLE auth_otp_challenges ADD CONSTRAINT auth_otp_challenges_purpose_check
    CHECK (purpose IN ('LOGIN','REGISTRATION','PASSWORD_RESET',
                      'ACCOUNT_DEACTIVATION','ACCOUNT_DELETION','REACTIVATION'));

-- Future account transitions are auditable without changing any existing account data.
CREATE TABLE account_status_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    previous_status VARCHAR(20) NOT NULL,
    new_status VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX account_status_events_user_time ON account_status_events(user_id, occurred_at DESC);
CREATE TRIGGER account_status_events_append_only BEFORE UPDATE OR DELETE ON account_status_events
    FOR EACH ROW EXECUTE FUNCTION reject_admin_audit_mutation();
CREATE TRIGGER account_status_events_no_truncate BEFORE TRUNCATE ON account_status_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_admin_audit_mutation();
CREATE FUNCTION audit_self_service_account_status() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.account_status IS DISTINCT FROM OLD.account_status
       AND (NEW.account_status IN ('DEACTIVATED','DELETION_PENDING')
            OR (OLD.account_status = 'DEACTIVATED' AND NEW.account_status = 'ACTIVE')) THEN
        INSERT INTO account_status_events(user_id, previous_status, new_status)
        VALUES (NEW.id, OLD.account_status, NEW.account_status);
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER users_self_service_status_audit AFTER UPDATE OF account_status ON users
    FOR EACH ROW EXECUTE FUNCTION audit_self_service_account_status();
ALTER TABLE account_status_events ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON account_status_events FROM PUBLIC;
ALTER TABLE account_deletion_requests ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON account_deletion_requests FROM PUBLIC;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='anon') THEN
        REVOKE ALL ON account_status_events,account_deletion_requests FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='authenticated') THEN
        REVOKE ALL ON account_status_events,account_deletion_requests FROM authenticated;
    END IF;
END $$;
