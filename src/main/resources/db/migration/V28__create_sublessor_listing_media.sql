CREATE TABLE sublessor_listing_media
(
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    sublessor_listing_id UUID        NOT NULL,
    media_type           VARCHAR(10) NOT NULL,
    object_path          VARCHAR(500) NOT NULL,
    content_type         VARCHAR(50)  NOT NULL,
    size_bytes           BIGINT      NOT NULL,
    sort_order           SMALLINT    NOT NULL DEFAULT 0,
    confirmed_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT sublessor_listing_media_listing_foreign_key
        FOREIGN KEY (sublessor_listing_id)
            REFERENCES sublessor_listings (id)
            ON DELETE CASCADE,

    CONSTRAINT sublessor_listing_media_type_check
        CHECK (media_type IN ('IMAGE', 'VIDEO')),

    CONSTRAINT sublessor_listing_media_content_type_check
        CHECK (
            content_type IN (
                             'image/jpeg',
                             'image/png',
                             'image/webp',
                             'video/mp4'
                )
            ),

    CONSTRAINT sublessor_listing_media_size_check
        CHECK (
            size_bytes > 0
                AND size_bytes <= 52428800
            ),

    CONSTRAINT sublessor_listing_media_sort_order_check
        CHECK (
            sort_order >= 0
                AND sort_order <= 30
            ),

    CONSTRAINT sublessor_listing_media_listing_order_unique
        UNIQUE (sublessor_listing_id, sort_order)
);

CREATE INDEX sublessor_listing_media_listing_index
    ON sublessor_listing_media (
                                sublessor_listing_id,
                                sort_order
        );