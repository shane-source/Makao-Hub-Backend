CREATE TABLE rental_applications
(
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    renter_id  UUID        NOT NULL,
    listing_id UUID        NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT rental_applications_renter_foreign_key
        FOREIGN KEY (renter_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT rental_applications_listing_foreign_key
        FOREIGN KEY (listing_id)
            REFERENCES listings (id)
            ON DELETE CASCADE,

    CONSTRAINT rental_applications_status_check
        CHECK (
            status IN (
                       'SUBMITTED',
                       'UNDER_REVIEW',
                       'APPROVED',
                       'REJECTED',
                       'WITHDRAWN'
                )
            ),

    CONSTRAINT rental_applications_unique_renter_listing
        UNIQUE (renter_id, listing_id)
);

CREATE INDEX rental_applications_renter_index
    ON rental_applications (renter_id, created_at DESC);

CREATE INDEX rental_applications_listing_index
    ON rental_applications (listing_id, status);