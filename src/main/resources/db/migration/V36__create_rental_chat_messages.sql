CREATE TABLE rental_chat_messages
(
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_thread_id    UUID        NOT NULL,
    sender_id         UUID        NOT NULL,
    client_message_id UUID        NOT NULL,
    masked_body       TEXT        NOT NULL,
    moderation_status VARCHAR(20) NOT NULL,
    moderation_reason VARCHAR(50),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    /*
     * Each message belongs to an existing Renter-Landlord thread.
     * Preserve its history by preventing deletion of referenced threads.
     */
    CONSTRAINT rental_chat_messages_thread_foreign_key
        FOREIGN KEY (chat_thread_id)
            REFERENCES rental_chat_threads (id)
            ON DELETE RESTRICT,

    /*
     * The sender must exist.
     * Thread participation must also be checked by the message service.
     */
    CONSTRAINT rental_chat_messages_sender_foreign_key
        FOREIGN KEY (sender_id)
            REFERENCES users (id)
            ON DELETE RESTRICT,

    /*
     * Retrying the same logical message cannot create another row.
     */
    CONSTRAINT rental_chat_messages_sender_client_message_unique
        UNIQUE (sender_id, client_message_id),

    CONSTRAINT rental_chat_messages_body_length_check
        CHECK (
            char_length(masked_body) BETWEEN 1 AND 2000
                AND masked_body ~ '[^[:space:]]'
            ),

    CONSTRAINT rental_chat_messages_moderation_status_check
        CHECK (
            moderation_status IN ('CLEAN', 'FLAGGED')
            ),

    /*
     * Only the existing contact-sharing reason is currently supported.
     * Never store a detected phone number or email as the reason.
     */
    CONSTRAINT rental_chat_messages_moderation_consistency_check
        CHECK (
            (
                moderation_status = 'CLEAN'
                    AND moderation_reason IS NULL
                )
                OR
            (
                moderation_status = 'FLAGGED'
                    AND moderation_reason IS NOT NULL
                    AND moderation_reason = 'CONTACT_DETAILS'
                )
            )
);

/*
 * Supports message-history queries within a thread.
 * UUID ordering breaks ties when timestamps are equal.
 */
CREATE INDEX rental_chat_messages_thread_cursor_index
    ON rental_chat_messages (
                             chat_thread_id,
                             created_at DESC,
                             id DESC
        );

/*
 * Supports the future Admin queue without indexing every clean message.
 */
CREATE INDEX rental_chat_messages_flagged_index
    ON rental_chat_messages (
                             created_at ASC,
                             id ASC
        )
    WHERE moderation_status = 'FLAGGED';