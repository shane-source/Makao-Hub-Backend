-- Staff may clear a flagged message that is a false positive. The message stays masked.
ALTER TABLE admin_message_reviews DROP CONSTRAINT IF EXISTS admin_message_reviews_state_check;
ALTER TABLE admin_message_reviews ADD CONSTRAINT admin_message_reviews_state_check CHECK (state IN ('WARNED','ESCALATED','CLEARED'));
