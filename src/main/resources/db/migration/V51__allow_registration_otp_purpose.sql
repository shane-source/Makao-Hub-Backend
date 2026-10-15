ALTER TABLE auth_otp_challenges
    DROP CONSTRAINT IF EXISTS auth_otp_challenges_purpose_check;

ALTER TABLE auth_otp_challenges
    ADD CONSTRAINT auth_otp_challenges_purpose_check
        CHECK (purpose IN ('REGISTRATION', 'LOGIN', 'PASSWORD_RESET'));
