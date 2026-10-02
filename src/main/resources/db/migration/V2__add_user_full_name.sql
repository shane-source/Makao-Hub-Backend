ALTER TABLE users
    ADD COLUMN full_name VARCHAR(150) NOT NULL;

ALTER TABLE users
    ALTER COLUMN phone_ciphertext SET NOT NULL;

ALTER TABLE users
    ALTER COLUMN phone_lookup_hash SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT users_full_name_length_check
        CHECK (
            CHAR_LENGTH(BTRIM(full_name))
                BETWEEN 2 AND 150
            );