-- Additive only: preserve accounts, historical OTP rows and applied V52.
-- Old SMS challenges have no digest and cannot authenticate via email OTP.
ALTER TABLE auth_otp_challenges
    ADD COLUMN code_digest VARCHAR(64),
    ADD CONSTRAINT auth_otp_challenges_digest_check
        CHECK (code_digest IS NULL OR code_digest ~ '^[0-9a-f]{64}$');
