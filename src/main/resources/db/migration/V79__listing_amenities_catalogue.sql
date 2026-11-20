CREATE TABLE amenity_catalogue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(80) NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX amenity_catalogue_name_idx ON amenity_catalogue (lower(trim(name)));
CREATE TABLE landlord_listing_amenities (
    listing_id UUID NOT NULL REFERENCES listings(id) ON DELETE CASCADE,
    amenity_id UUID NOT NULL REFERENCES amenity_catalogue(id),
    PRIMARY KEY (listing_id, amenity_id)
);
CREATE TABLE sublessor_listing_amenities (
    listing_id UUID NOT NULL REFERENCES sublessor_listings(id) ON DELETE CASCADE,
    amenity_id UUID NOT NULL REFERENCES amenity_catalogue(id),
    PRIMARY KEY (listing_id, amenity_id)
);
ALTER TABLE amenity_catalogue ENABLE ROW LEVEL SECURITY;
ALTER TABLE landlord_listing_amenities ENABLE ROW LEVEL SECURITY;
ALTER TABLE sublessor_listing_amenities ENABLE ROW LEVEL SECURITY;
INSERT INTO amenity_catalogue(name) VALUES ('Wi-Fi'), ('Water'), ('Security'), ('Parking'), ('Balcony'), ('Pets allowed');

-- Preserve amenities already stored by existing owner screens.
INSERT INTO landlord_listing_amenities(listing_id,amenity_id)
SELECT l.id,a.id FROM listings l JOIN amenity_catalogue a ON
    (a.name='Water' AND l.water_available IS TRUE) OR
    (a.name='Security' AND l.security_available IS TRUE) OR
    (a.name='Parking' AND l.parking_available IS TRUE) OR
    (a.name='Balcony' AND l.balcony IS TRUE) OR
    (a.name='Pets allowed' AND l.pets_allowed IS TRUE);
INSERT INTO sublessor_listing_amenities(listing_id,amenity_id)
SELECT h.listing_id,a.id FROM sublessor_household_details h JOIN amenity_catalogue a ON
    (a.name='Water' AND 'WATER'=ANY(string_to_array(h.amenities,','))) OR
    (a.name='Wi-Fi' AND 'WIFI'=ANY(string_to_array(h.amenities,','))) OR
    (a.name='Security' AND 'SECURITY'=ANY(string_to_array(h.amenities,','))) OR
    (a.name='Parking' AND 'PARKING'=ANY(string_to_array(h.amenities,','))) OR
    (a.name='Pets allowed' AND 'PETS'=ANY(string_to_array(h.amenities,',')));

-- Only notifications created after this migration are eligible for the default listing email worker.
CREATE TABLE listing_email_activation (id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id), enabled_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP);
INSERT INTO listing_email_activation(id) VALUES (TRUE);
ALTER TABLE listing_email_activation ENABLE ROW LEVEL SECURITY;
