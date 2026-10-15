CREATE TABLE sublessor_rental_applications
(
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    renter_id            UUID        NOT NULL,
    sublessor_listing_id UUID        NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT sublessor_rental_applications_renter_foreign_key
        FOREIGN KEY (renter_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT sublessor_rental_applications_listing_foreign_key
        FOREIGN KEY (sublessor_listing_id)
            REFERENCES sublessor_listings (id)
            ON DELETE CASCADE,

    CONSTRAINT sublessor_rental_applications_status_check
        CHECK (
            status IN (
                       'SUBMITTED',
                       'UNDER_REVIEW',
                       'APPROVED',
                       'REJECTED',
                       'WITHDRAWN'
                )
            ),

    CONSTRAINT sublessor_rental_applications_unique_renter_listing
        UNIQUE (renter_id, sublessor_listing_id)
);

CREATE INDEX sublessor_rental_applications_renter_index
    ON sublessor_rental_applications (
                                      renter_id,
                                      created_at DESC
        );

CREATE INDEX sublessor_rental_applications_listing_index
    ON sublessor_rental_applications (
                                      sublessor_listing_id,
                                      status
        );