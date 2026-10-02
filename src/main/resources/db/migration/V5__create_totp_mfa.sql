CREATE TABLE mfa_totp_credentials
(
    user_id                 UUID        PRIMARY KEY,
    secret_ciphertext       BYTEA       NOT NULL,
    secret_key_version      SMALLINT    NOT NULL DEFAULT 1,
    enabled                 BOOLEAN     NOT NULL DEFAULT FALSE,
    verified_at             TIMESTAMPTZ,
    last_accepted_time_step BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT mfa_totp_user_foreign_key
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT mfa_totp_key_version_check
        CHECK (secret_key_version > 0),

    CONSTRAINT mfa_totp_time_step_check
        CHECK (
            last_accepted_time_step IS NULL
                OR last_accepted_time_step >= 0
            ),

    CONSTRAINT mfa_totp_enabled_check
        CHECK (
            enabled = FALSE
                OR verified_at IS NOT NULL
            )
);


CREATE TABLE mfa_login_challenges
(
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL,
    attempt_count SMALLINT    NOT NULL DEFAULT 0,
    expires_at    TIMESTAMPTZ NOT NULL,
    consumed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT mfa_challenges_user_foreign_key
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT mfa_challenges_attempt_count_check
        CHECK (
            attempt_count BETWEEN 0 AND 5
            ),

    CONSTRAINT mfa_challenges_expiry_check
        CHECK (
            expires_at > created_at
            ),

    CONSTRAINT mfa_challenges_consumed_at_check
        CHECK (
            consumed_at IS NULL
                OR consumed_at >= created_at
            )
);

CREATE INDEX mfa_challenges_user_id_index
    ON mfa_login_challenges (user_id);

CREATE INDEX mfa_challenges_active_expiry_index
    ON mfa_login_challenges (expires_at)
    WHERE consumed_at IS NULL;