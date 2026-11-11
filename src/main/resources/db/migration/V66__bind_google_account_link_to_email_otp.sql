-- Additive only: existing accounts, identities and OTP challenges are unchanged.
ALTER TABLE auth_otp_challenges
    ADD COLUMN pending_google_subject VARCHAR(255),
    ADD CONSTRAINT ck_otp_google_subject_login CHECK (
        pending_google_subject IS NULL OR
        (purpose = 'LOGIN' AND length(trim(pending_google_subject)) > 0)
    );
