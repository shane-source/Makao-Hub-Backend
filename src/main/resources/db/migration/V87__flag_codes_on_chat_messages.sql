-- Safe category labels for flagged messages (phone, email, web_link, social_handle, numeric_group_limit).
ALTER TABLE rental_chat_messages ADD COLUMN IF NOT EXISTS flag_codes VARCHAR(100);
ALTER TABLE roommate_chat_messages ADD COLUMN IF NOT EXISTS flag_codes VARCHAR(100);
