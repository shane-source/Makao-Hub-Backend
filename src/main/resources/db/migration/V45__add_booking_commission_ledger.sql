ALTER TABLE bookings
    ADD COLUMN commission_amount_minor BIGINT NOT NULL DEFAULT 0;

ALTER TABLE bookings
    DROP CONSTRAINT bookings_amounts_check;

ALTER TABLE bookings
    ADD CONSTRAINT bookings_amounts_check
    CHECK (
        rent_amount_minor > 0
        AND deposit_amount_minor > 0
        AND commission_amount_minor >= 0
        AND total_amount_minor = rent_amount_minor
            + deposit_amount_minor
            + commission_amount_minor
    );

ALTER TABLE ledger_entries
    DROP CONSTRAINT ledger_account_type_check;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_account_type_check
    CHECK (account_type IN (
        'RENTER_CLEARING', 'ESCROW_HOLD', 'PLATFORM_REVENUE'
    ));

ALTER TABLE ledger_entries
    DROP CONSTRAINT ledger_component_check;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_component_check
    CHECK (component IN ('RENT', 'DEPOSIT', 'COMMISSION'));
