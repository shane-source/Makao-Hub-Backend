CREATE TABLE refresh_tokens
(
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID        NOT NULL,
    token_hash           VARCHAR(64) NOT NULL,
    token_family_id      UUID        NOT NULL,
    replaced_by_token_id UUID,
    issued_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at           TIMESTAMPTZ NOT NULL,
    used_at              TIMESTAMPTZ,
    revoked_at           TIMESTAMPTZ,
    revocation_reason    VARCHAR(50),

    CONSTRAINT refresh_tokens_token_hash_unique
        UNIQUE (token_hash),

    CONSTRAINT refresh_tokens_replacement_unique
        UNIQUE (replaced_by_token_id),

    CONSTRAINT refresh_tokens_user_foreign_key
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT refresh_tokens_replacement_foreign_key
        FOREIGN KEY (replaced_by_token_id)
            REFERENCES refresh_tokens (id)
            ON DELETE SET NULL,

    CONSTRAINT refresh_tokens_expiry_check
        CHECK (expires_at > issued_at),

    CONSTRAINT refresh_tokens_revocation_check
        CHECK (
            (revoked_at IS NULL AND revocation_reason IS NULL)
                OR
            (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
            )
);

CREATE INDEX refresh_tokens_user_id_index
    ON refresh_tokens (user_id);

CREATE INDEX refresh_tokens_family_id_index
    ON refresh_tokens (token_family_id);

CREATE INDEX refresh_tokens_active_expiry_index
    ON refresh_tokens (expires_at)
    WHERE revoked_at IS NULL;