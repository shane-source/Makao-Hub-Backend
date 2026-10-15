CREATE TABLE rental_chat_threads
(
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    rental_application_id UUID       NOT NULL,

    renter_id            UUID        NOT NULL,

    landlord_id          UUID        NOT NULL,

    status               VARCHAR(20) NOT NULL DEFAULT 'OPEN',

    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    closed_at            TIMESTAMPTZ,

    CONSTRAINT rental_chat_threads_application_foreign_key
        FOREIGN KEY (rental_application_id)
            REFERENCES rental_applications (id)
            ON DELETE CASCADE,

    CONSTRAINT rental_chat_threads_renter_foreign_key
        FOREIGN KEY (renter_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT rental_chat_threads_landlord_foreign_key
        FOREIGN KEY (landlord_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT rental_chat_threads_unique_application
        UNIQUE (rental_application_id),

    CONSTRAINT rental_chat_threads_different_users
        CHECK (renter_id <> landlord_id),

    CONSTRAINT rental_chat_threads_status_check
        CHECK (
            status IN (
                       'OPEN',
                       'CLOSED'
                )
            )
);

CREATE INDEX rental_chat_threads_renter_index
    ON rental_chat_threads (
                            renter_id,
                            created_at DESC,
                            id DESC
        );

CREATE INDEX rental_chat_threads_landlord_index
    ON rental_chat_threads (
                            landlord_id,
                            created_at DESC,
                            id DESC
        );

CREATE INDEX rental_chat_threads_application_index
    ON rental_chat_threads (
                            rental_application_id
        );