-- No backfill: old messages are not assumed delivered.
ALTER TABLE rental_chat_messages ADD COLUMN delivered_at TIMESTAMPTZ;
ALTER TABLE roommate_chat_messages ADD COLUMN delivered_at TIMESTAMPTZ;
