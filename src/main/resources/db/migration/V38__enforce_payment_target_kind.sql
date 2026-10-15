ALTER TABLE payment_intents
    ADD COLUMN target_kind VARCHAR(24) NOT NULL DEFAULT 'NONE';

ALTER TABLE payment_intents
    ALTER COLUMN target_kind DROP DEFAULT;

ALTER TABLE payment_intents
    DROP CONSTRAINT payment_intents_target_check;

ALTER TABLE payment_intents
    ADD CONSTRAINT payment_intents_target_check
    CHECK (
        (payment_type = 'BOOKING'
            AND booking_id IS NOT NULL
            AND listing_id IS NULL
            AND target_kind = 'BOOKING')
        OR
        (payment_type IN (
                'EXACT_LOCATION_UNLOCK', 'VIDEO_UNLOCK',
                'PHONE_UNLOCK', 'BOOST', 'LISTING_ADD_ON'
            )
            AND listing_id IS NOT NULL
            AND booking_id IS NULL
            AND target_kind IN ('LANDLORD_LISTING', 'SUBLESSOR_LISTING'))
        OR
        (payment_type = 'PLAN_UPGRADE'
            AND listing_id IS NULL
            AND booking_id IS NULL
            AND target_kind = 'NONE')
    );
