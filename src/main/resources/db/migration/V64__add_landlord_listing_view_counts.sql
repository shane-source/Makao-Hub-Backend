CREATE TABLE landlord_listing_view_counts (
    listing_id UUID PRIMARY KEY REFERENCES listings(id) ON DELETE CASCADE,
    detail_opens BIGINT NOT NULL DEFAULT 0 CHECK (detail_opens >= 0)
);
