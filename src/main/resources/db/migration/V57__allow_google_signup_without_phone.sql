-- Google signup may omit a phone. Existing phone values remain unchanged.
ALTER TABLE users
    ALTER COLUMN phone_ciphertext DROP NOT NULL,
    ALTER COLUMN phone_lookup_hash DROP NOT NULL;

-- A phone is either absent or stored with both protection and lookup data.
ALTER TABLE users ADD CONSTRAINT users_phone_pair_present
    CHECK ((phone_ciphertext IS NULL) = (phone_lookup_hash IS NULL));
