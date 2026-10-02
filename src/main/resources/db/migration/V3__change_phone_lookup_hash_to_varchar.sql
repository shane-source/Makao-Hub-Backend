ALTER TABLE users
    ALTER COLUMN phone_lookup_hash
        TYPE VARCHAR(64)
        USING BTRIM(phone_lookup_hash);