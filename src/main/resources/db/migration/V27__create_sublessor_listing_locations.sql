CREATE TABLE sublessor_listing_locations
(
    sublessor_listing_id          UUID         PRIMARY KEY,
    area_label                    VARCHAR(150) NOT NULL,
    exact_coordinates_ciphertext  BYTEA        NOT NULL,
    approximate_latitude          NUMERIC(9, 6) NOT NULL,
    approximate_longitude         NUMERIC(9, 6) NOT NULL,
    approximate_radius_meters     SMALLINT     NOT NULL,
    created_at                    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT sublessor_listing_locations_listing_foreign_key
        FOREIGN KEY (sublessor_listing_id)
            REFERENCES sublessor_listings (id)
            ON DELETE CASCADE,

    CONSTRAINT sublessor_listing_locations_latitude_check
        CHECK (approximate_latitude BETWEEN -5.0 AND 5.5),

    CONSTRAINT sublessor_listing_locations_longitude_check
        CHECK (approximate_longitude BETWEEN 33.0 AND 42.5),

    CONSTRAINT sublessor_listing_locations_radius_check
        CHECK (approximate_radius_meters BETWEEN 1 AND 1000)
);