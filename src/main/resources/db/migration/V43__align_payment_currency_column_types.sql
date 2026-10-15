ALTER TABLE payment_intents
    ALTER COLUMN currency TYPE VARCHAR(3)
        USING BTRIM(currency);

ALTER TABLE bookings
    ALTER COLUMN currency TYPE VARCHAR(3)
        USING BTRIM(currency);

ALTER TABLE ledger_entries
    ALTER COLUMN currency TYPE VARCHAR(3)
        USING BTRIM(currency);

ALTER TABLE payment_audit_events
    ALTER COLUMN currency TYPE VARCHAR(3)
        USING BTRIM(currency);

ALTER TABLE listing_unlock_entitlements
    ALTER COLUMN currency TYPE VARCHAR(3)
        USING BTRIM(currency);
