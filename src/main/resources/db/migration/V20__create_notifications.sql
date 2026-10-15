CREATE TABLE notifications
(
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL,
    type       VARCHAR(40) NOT NULL,
    title      VARCHAR(150) NOT NULL,
    body       VARCHAR(500) NOT NULL,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT notifications_user_foreign_key
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE
);

CREATE INDEX notifications_user_created_index
    ON notifications (user_id, created_at DESC);

CREATE INDEX notifications_unread_index
    ON notifications (user_id, read_at)
    WHERE read_at IS NULL;