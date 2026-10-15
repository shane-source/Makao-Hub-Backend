CREATE TABLE saved_listings
(
    user_id    UUID        NOT NULL,
    listing_id UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT saved_listings_primary_key
        PRIMARY KEY (user_id, listing_id),

    CONSTRAINT saved_listings_user_foreign_key
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT saved_listings_listing_foreign_key
        FOREIGN KEY (listing_id)
            REFERENCES listings (id)
            ON DELETE CASCADE
);

CREATE INDEX saved_listings_user_index
    ON saved_listings (user_id, created_at DESC);

CREATE INDEX saved_listings_listing_index
    ON saved_listings (listing_id);