-- Per-person view of a conversation: archived (kept, moved out of the inbox) or deleted (hidden for this person only).
-- The other participant is never affected, and the messages themselves are never removed.
CREATE TABLE chat_thread_user_states
(
    kind       VARCHAR(10) NOT NULL,
    thread_id  UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    state      VARCHAR(10) NOT NULL,
    cleared_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (kind, thread_id, user_id),

    CONSTRAINT chat_thread_user_states_user_foreign_key
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT chat_thread_user_states_kind_check
        CHECK (kind IN ('RENTAL', 'ROOMMATE')),

    CONSTRAINT chat_thread_user_states_state_check
        CHECK (state IN ('ARCHIVED', 'DELETED'))
);

CREATE INDEX chat_thread_user_states_user_index
    ON chat_thread_user_states (user_id);
