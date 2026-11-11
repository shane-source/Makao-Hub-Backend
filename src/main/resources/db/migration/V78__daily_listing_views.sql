-- Counts begin at installation; prior individual view times are not available.
CREATE TABLE landlord_listing_daily_views (
    listing_id UUID NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
    view_date DATE NOT NULL,
    detail_opens BIGINT NOT NULL DEFAULT 0 CHECK (detail_opens >= 0),
    PRIMARY KEY (listing_id, view_date)
);
CREATE TABLE sublessor_listing_daily_views (
    listing_id UUID NOT NULL REFERENCES sublessor_listings(id) ON DELETE CASCADE,
    view_date DATE NOT NULL,
    detail_opens BIGINT NOT NULL DEFAULT 0 CHECK (detail_opens >= 0),
    PRIMARY KEY (listing_id, view_date)
);
ALTER TABLE landlord_listing_daily_views ENABLE ROW LEVEL SECURITY;
ALTER TABLE sublessor_listing_daily_views ENABLE ROW LEVEL SECURITY;
