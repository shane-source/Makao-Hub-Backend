ALTER TABLE chat_unlock_entitlements
    ALTER COLUMN currency TYPE VARCHAR(3)
        USING BTRIM(currency);