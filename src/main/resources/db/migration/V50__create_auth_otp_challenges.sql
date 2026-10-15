CREATE TABLE auth_otp_challenges
(
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose VARCHAR(30) NOT NULL CHECK (purpose IN ('LOGIN', 'PASSWORD_RESET')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'VERIFIED', 'EXPIRED', 'LOCKED')),
    authorization_version BIGINT NOT NULL CHECK (authorization_version >= 0),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0 AND attempt_count <= 5),
    verified_at TIMESTAMPTZ,
    CONSTRAINT auth_otp_challenges_expiry_check CHECK (expires_at > issued_at),
    CONSTRAINT auth_otp_challenges_verified_check CHECK ((status <> 'VERIFIED' AND verified_at IS NULL) OR (status = 'VERIFIED' AND verified_at IS NOT NULL))
);
CREATE INDEX auth_otp_challenges_user_purpose_index ON auth_otp_challenges (user_id, purpose, issued_at DESC);
CREATE INDEX auth_otp_challenges_pending_index ON auth_otp_challenges (user_id, purpose, status) WHERE status = 'PENDING';
ALTER TABLE auth_otp_challenges ENABLE ROW LEVEL SECURITY;
