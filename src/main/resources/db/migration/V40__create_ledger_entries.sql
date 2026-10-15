CREATE TABLE ledger_entries
(
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id     UUID NOT NULL,
    payment_intent_id  UUID NOT NULL,
    booking_id         UUID NOT NULL,
    account_type       VARCHAR(24) NOT NULL,
    component          VARCHAR(16) NOT NULL,
    side               VARCHAR(8) NOT NULL,
    amount_minor       BIGINT NOT NULL,
    currency           CHAR(3) NOT NULL DEFAULT 'KES',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT ledger_payment_intent_foreign_key
        FOREIGN KEY (payment_intent_id) REFERENCES payment_intents (id)
        ON DELETE RESTRICT,
    CONSTRAINT ledger_booking_foreign_key
        FOREIGN KEY (booking_id) REFERENCES bookings (id)
        ON DELETE RESTRICT,
    CONSTRAINT ledger_account_type_check
        CHECK (account_type IN ('RENTER_CLEARING', 'ESCROW_HOLD')),
    CONSTRAINT ledger_component_check
        CHECK (component IN ('RENT', 'DEPOSIT')),
    CONSTRAINT ledger_side_check
        CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_amount_check
        CHECK (amount_minor > 0),
    CONSTRAINT ledger_currency_check
        CHECK (currency = 'KES'),
    CONSTRAINT ledger_payment_line_unique
        UNIQUE (payment_intent_id, component, side)
);

CREATE INDEX ledger_booking_created_index
    ON ledger_entries (booking_id, created_at ASC, id ASC);

CREATE INDEX ledger_transaction_index
    ON ledger_entries (transaction_id, id ASC);
