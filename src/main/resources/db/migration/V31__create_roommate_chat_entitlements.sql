CREATE TABLE roommate_chat_threads
(
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    roommate_interest_id UUID        NOT NULL,
    renter_id            UUID        NOT NULL,
    sublessor_id         UUID        NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'LOCKED',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at            TIMESTAMPTZ,

    CONSTRAINT roommate_chat_threads_interest_foreign_key
        FOREIGN KEY (roommate_interest_id)
            REFERENCES roommate_interests (id)
            ON DELETE CASCADE,

    CONSTRAINT roommate_chat_threads_renter_foreign_key
        FOREIGN KEY (renter_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT roommate_chat_threads_sublessor_foreign_key
        FOREIGN KEY (sublessor_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT roommate_chat_threads_unique_interest
        UNIQUE (roommate_interest_id),

    CONSTRAINT roommate_chat_threads_different_users
        CHECK (renter_id <> sublessor_id),

    CONSTRAINT roommate_chat_threads_status_check
        CHECK (
            status IN (
                       'LOCKED',
                       'OPEN',
                       'CLOSED'
                )
            )
);

CREATE INDEX roommate_chat_threads_renter_index
    ON roommate_chat_threads (
                              renter_id,
                              status,
                              created_at DESC
        );

CREATE INDEX roommate_chat_threads_sublessor_index
    ON roommate_chat_threads (
                              sublessor_id,
                              status,
                              created_at DESC
        );

CREATE TABLE chat_unlock_entitlements
(
    id                         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_thread_id             UUID        NOT NULL,
    payer_user_id              UUID        NOT NULL,
    status                     VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount_minor               BIGINT      NOT NULL,
    currency                   CHAR(3)     NOT NULL DEFAULT 'KES',
    idempotency_key            VARCHAR(100) NOT NULL,
    provider_payment_reference VARCHAR(120),
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at               TIMESTAMPTZ,
    revoked_at                 TIMESTAMPTZ,

    CONSTRAINT chat_unlock_thread_foreign_key
        FOREIGN KEY (chat_thread_id)
            REFERENCES roommate_chat_threads (id)
            ON DELETE CASCADE,

    CONSTRAINT chat_unlock_payer_foreign_key
        FOREIGN KEY (payer_user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT chat_unlock_amount_check
        CHECK (amount_minor > 0),

    CONSTRAINT chat_unlock_status_check
        CHECK (
            status IN (
                       'PENDING',
                       'CONFIRMED',
                       'REVOKED',
                       'REFUNDED',
                       'FAILED'
                )
            ),

    CONSTRAINT chat_unlock_idempotency_unique
        UNIQUE (payer_user_id, idempotency_key),

    CONSTRAINT chat_unlock_provider_reference_unique
        UNIQUE (provider_payment_reference)
);

CREATE INDEX chat_unlock_thread_status_index
    ON chat_unlock_entitlements (
                                 chat_thread_id,
                                 status,
                                 created_at DESC
        );

CREATE INDEX chat_unlock_payer_index
    ON chat_unlock_entitlements (
                                 payer_user_id,
                                 created_at DESC
        );