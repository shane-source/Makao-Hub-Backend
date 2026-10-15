CREATE TABLE roommate_interests
(
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    renter_id            UUID        NOT NULL,
    sublessor_listing_id UUID        NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT roommate_interests_renter_foreign_key
        FOREIGN KEY (renter_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT roommate_interests_listing_foreign_key
        FOREIGN KEY (sublessor_listing_id)
            REFERENCES sublessor_listings (id)
            ON DELETE CASCADE,

    CONSTRAINT roommate_interests_status_check
        CHECK (
            status IN (
                       'SUBMITTED',
                       'UNDER_REVIEW',
                       'ACCEPTED',
                       'DECLINED',
                       'WITHDRAWN'
                )
            ),

    CONSTRAINT roommate_interests_unique_renter_listing
        UNIQUE (renter_id, sublessor_listing_id)
);

CREATE INDEX roommate_interests_renter_index
    ON roommate_interests (
                           renter_id,
                           created_at DESC
        );

CREATE INDEX roommate_interests_listing_index
    ON roommate_interests (
                           sublessor_listing_id,
                           status
        );