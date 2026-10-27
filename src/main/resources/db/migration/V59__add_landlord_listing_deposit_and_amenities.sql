-- Landlord screen contract: preserve the owner-entered deposit and amenity choices.
-- Columns are nullable so this migration does not invent values for existing listings.
ALTER TABLE listings
    ADD COLUMN deposit_kes BIGINT CHECK (deposit_kes IS NULL OR deposit_kes >= 0),
    ADD COLUMN water_available BOOLEAN,
    ADD COLUMN security_available BOOLEAN,
    ADD COLUMN parking_available BOOLEAN,
    ADD COLUMN balcony BOOLEAN,
    ADD COLUMN pets_allowed BOOLEAN;
