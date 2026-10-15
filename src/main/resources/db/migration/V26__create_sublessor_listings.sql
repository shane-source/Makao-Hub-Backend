CREATE TABLE sublessor_listings
(
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    sublessor_id     UUID         NOT NULL,
    title            VARCHAR(150) NOT NULL,
    description      VARCHAR(3000) NOT NULL,
    space_type       VARCHAR(30)  NOT NULL,
    monthly_rent_kes BIGINT       NOT NULL,
    bathroom_count   SMALLINT     NOT NULL DEFAULT 1,
    furnished        BOOLEAN      NOT NULL DEFAULT FALSE,
    available_from   DATE,
    status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT sublessor_listings_sublessor_foreign_key
        FOREIGN KEY (sublessor_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT sublessor_listings_space_type_check
        CHECK (
            space_type IN (
                           'SPARE_ROOM',
                           'SERVANT_QUARTER'
                )
            ),

    CONSTRAINT sublessor_listings_status_check
        CHECK (
            status IN (
                       'DRAFT',
                       'PUBLISHED',
                       'PAUSED',
                       'ARCHIVED'
                )
            ),

    CONSTRAINT sublessor_listings_monthly_rent_check
        CHECK (monthly_rent_kes > 0),

    CONSTRAINT sublessor_listings_bathroom_count_check
        CHECK (bathroom_count >= 1)
);

CREATE INDEX sublessor_listings_sublessor_index
    ON sublessor_listings (sublessor_id);

CREATE INDEX sublessor_listings_status_index
    ON sublessor_listings (status);