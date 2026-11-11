-- New sends only: do not infer or rewrite historic, already-masked messages.
-- Store capped counts rather than contact digits; each sender has a separate count.
CREATE TABLE rental_chat_numeric_counts (
    chat_thread_id UUID NOT NULL REFERENCES rental_chat_threads(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    group_count SMALLINT NOT NULL CHECK (group_count BETWEEN 1 AND 6),
    PRIMARY KEY (chat_thread_id, sender_id)
);

CREATE TABLE roommate_chat_numeric_counts (
    chat_thread_id UUID NOT NULL REFERENCES roommate_chat_threads(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    group_count SMALLINT NOT NULL CHECK (group_count BETWEEN 1 AND 6),
    PRIMARY KEY (chat_thread_id, sender_id)
);
