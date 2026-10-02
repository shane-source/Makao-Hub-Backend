CREATE TABLE landlord_verifications
(
    user_id          UUID        PRIMARY KEY,
    status           VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    submitted_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at      TIMESTAMPTZ,
    reviewed_by      UUID,
    rejection_reason VARCHAR(500),

    CONSTRAINT landlord_verifications_user_foreign_key
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT landlord_verifications_reviewer_foreign_key
        FOREIGN KEY (reviewed_by)
            REFERENCES users (id)
            ON DELETE SET NULL,

    CONSTRAINT landlord_verifications_status_check
        CHECK (status IN (
                          'NOT_STARTED',
                          'PENDING',
                          'VERIFIED',
                          'REJECTED'
            ))
);

CREATE INDEX landlord_verifications_status_index
    ON landlord_verifications (status);