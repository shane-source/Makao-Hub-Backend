CREATE TABLE listing_media
(
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id   UUID        NOT NULL,
    media_type   VARCHAR(10) NOT NULL,
    object_path  VARCHAR(500) NOT NULL,
    content_type VARCHAR(50) NOT NULL,
    size_bytes   BIGINT      NOT NULL,
    sort_order   SMALLINT    NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT listing_media_listing_foreign_key
        FOREIGN KEY (listing_id)
            REFERENCES listings (id)
            ON DELETE CASCADE,

    CONSTRAINT listing_media_type_check
        CHECK (media_type IN ('IMAGE', 'VIDEO')),

    CONSTRAINT listing_media_content_type_check
        CHECK (
            content_type IN (
                             'image/jpeg',
                             'image/png',
                             'image/webp',
                             'video/mp4'
                )
            ),

    CONSTRAINT listing_media_size_check
        CHECK (size_bytes > 0 AND size_bytes <= 52428800),

    CONSTRAINT listing_media_sort_order_check
        CHECK (sort_order >= 0 AND sort_order <= 30),

    CONSTRAINT listing_media_listing_order_unique
        UNIQUE (listing_id, sort_order)
);

CREATE INDEX listing_media_listing_index
    ON listing_media (listing_id, sort_order);