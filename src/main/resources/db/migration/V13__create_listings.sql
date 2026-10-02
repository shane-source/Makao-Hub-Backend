CREATE TABLE listings
(
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    landlord_id      UUID         NOT NULL,
    title            VARCHAR(150) NOT NULL,
    description      VARCHAR(3000) NOT NULL,
    property_type    VARCHAR(30)  NOT NULL,
    monthly_rent_kes BIGINT       NOT NULL,
    bedroom_count    SMALLINT     NOT NULL,
    bathroom_count   SMALLINT     NOT NULL,
    furnished        BOOLEAN      NOT NULL DEFAULT FALSE,
    available_from   DATE,
    status           VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT listings_landlord_foreign_key
        FOREIGN KEY (landlord_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT listings_property_type_check
        CHECK (property_type IN (
                                 'APARTMENT',
                                 'HOUSE',
                                 'STUDIO',
                                 'BEDSITTER',
                                 'ROOM'
            )),

    CONSTRAINT listings_status_check
        CHECK (status IN (
                          'DRAFT',
                          'PUBLISHED',
                          'PAUSED',
                          'ARCHIVED'
            )),

    CONSTRAINT listings_monthly_rent_check
        CHECK (monthly_rent_kes > 0),

    CONSTRAINT listings_bedroom_count_check
        CHECK (bedroom_count >= 0),

    CONSTRAINT listings_bathroom_count_check
        CHECK (bathroom_count >= 1)
);

CREATE INDEX listings_landlord_index
    ON listings (landlord_id);

CREATE INDEX listings_status_index
    ON listings (status);