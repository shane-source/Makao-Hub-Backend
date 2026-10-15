CREATE TABLE roommate_chat_messages
(
    id                  UUID        PRIMARY KEY
        DEFAULT gen_random_uuid(),

    chat_thread_id      UUID        NOT NULL,

    sender_id           UUID        NOT NULL,

    client_message_id   UUID        NOT NULL,

    masked_body         TEXT        NOT NULL,

    moderation_status   VARCHAR(20) NOT NULL
        DEFAULT 'CLEAN',

    moderation_reason   VARCHAR(50),

    created_at          TIMESTAMPTZ NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT roommate_chat_messages_thread_foreign_key
        FOREIGN KEY (chat_thread_id)
            REFERENCES roommate_chat_threads (id)
            ON DELETE RESTRICT,

    CONSTRAINT roommate_chat_messages_sender_foreign_key
        FOREIGN KEY (sender_id)
            REFERENCES users (id)
            ON DELETE RESTRICT,

    CONSTRAINT roommate_chat_messages_sender_client_message_unique
        UNIQUE (sender_id, client_message_id),

    CONSTRAINT roommate_chat_messages_body_length_check
        CHECK (
            char_length(btrim(masked_body))
                BETWEEN 1 AND 2000
            ),

    CONSTRAINT roommate_chat_messages_moderation_status_check
        CHECK (
            moderation_status IN (
                                  'CLEAN',
                                  'FLAGGED'
                )
            ),

    CONSTRAINT roommate_chat_messages_moderation_consistency_check
        CHECK (
            (
                moderation_status = 'CLEAN'
                    AND moderation_reason IS NULL
                )
                OR
            (
                moderation_status = 'FLAGGED'
                    AND moderation_reason IS NOT NULL
                )
            )
);

CREATE INDEX roommate_chat_messages_thread_cursor_index
    ON roommate_chat_messages (
                               chat_thread_id,
                               created_at DESC,
                               id DESC
        );

CREATE INDEX roommate_chat_messages_sender_created_index
    ON roommate_chat_messages (
                               sender_id,
                               created_at DESC,
                               id DESC
        );

CREATE INDEX roommate_chat_messages_flagged_index
    ON roommate_chat_messages (
                               created_at ASC,
                               id ASC
        )
    WHERE moderation_status = 'FLAGGED';

CREATE INDEX roommate_chat_threads_renter_cursor_index
    ON roommate_chat_threads (
                              renter_id,
                              created_at DESC,
                              id DESC
        );

CREATE INDEX roommate_chat_threads_sublessor_cursor_index
    ON roommate_chat_threads (
                              sublessor_id,
                              created_at DESC,
                              id DESC
        );