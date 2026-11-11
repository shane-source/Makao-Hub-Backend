-- Only newly created notifications are queued. Existing notifications are not backfilled.
CREATE TABLE notification_email_outbox (
    notification_id UUID PRIMARY KEY REFERENCES notifications(id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX notification_email_pending_idx ON notification_email_outbox(created_at)
    WHERE state = 'PENDING';

-- Direct Supabase clients must not read or modify delivery metadata.
ALTER TABLE notification_email_outbox ENABLE ROW LEVEL SECURITY;

CREATE FUNCTION queue_notification_email() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO notification_email_outbox(notification_id) VALUES (NEW.id);
    RETURN NEW;
END;
$$;

CREATE TRIGGER queue_notification_email_after_insert
    AFTER INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION queue_notification_email();
