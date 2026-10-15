ALTER TABLE listing_media
    ADD COLUMN confirmed_at TIMESTAMPTZ NULL;

CREATE INDEX idx_listing_media_confirmed
    ON listing_media (listing_id, confirmed_at);