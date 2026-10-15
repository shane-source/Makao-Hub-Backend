-- Empty supplemental table. Existing landlord-only saved_listings records are not reinterpreted.
CREATE TABLE renter_saved_homes (
    renter_id UUID NOT NULL REFERENCES users(id),
    source VARCHAR(20) NOT NULL CHECK(source IN ('LANDLORD','SUBLESSOR')),
    listing_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(renter_id,source,listing_id)
);
