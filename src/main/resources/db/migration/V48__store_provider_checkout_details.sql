ALTER TABLE payment_intents
    ADD COLUMN authorization_url VARCHAR(500),
    ADD COLUMN provider_access_code VARCHAR(160),
    ADD COLUMN provider_display_text VARCHAR(500);
