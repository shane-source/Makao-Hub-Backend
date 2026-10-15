CREATE TABLE daraja_callback_receipts
(
    id                    UUID PRIMARY KEY,
    checkout_request_id   VARCHAR(120) NOT NULL,
    merchant_request_id   VARCHAR(120) NOT NULL,
    result_code           INTEGER NOT NULL,
    result_description    VARCHAR(1024) NOT NULL,
    mpesa_receipt_number  VARCHAR(64),
    amount_minor          BIGINT,
    received_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT daraja_callback_checkout_unique
        UNIQUE (checkout_request_id),

    CONSTRAINT daraja_callback_receipt_unique
        UNIQUE (mpesa_receipt_number),

    CONSTRAINT daraja_callback_amount_check
        CHECK (amount_minor IS NULL OR amount_minor > 0)
);

CREATE INDEX daraja_callback_received_index
    ON daraja_callback_receipts (received_at DESC, id DESC);
