-- The listing now leaves the marketplace when the money is released to the landlord or sublessor,
-- not at the moment the renter answers True. Sequence:
--   1. The renter answers True after the owner confirmed move-in. Any other unpaid bookings for the
--      listing are cancelled straight away, so nobody else can pay for it during the wait.
--   2. Five minutes later the payout is released (released_at is set). Only then is the listing
--      archived, so it disappears from the app and the website.
-- Done in the database so it holds no matter which service records the release.

CREATE OR REPLACE FUNCTION archive_listing_after_move_in() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    -- Step 1: the True answer. Close the door on other renters, keep the listing until release.
    IF NEW.move_in_accepted IS TRUE AND NEW.owner_move_in_confirmed_at IS NOT NULL
       AND (OLD.move_in_accepted IS DISTINCT FROM TRUE) THEN
        UPDATE bookings SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE listing_kind = NEW.listing_kind AND listing_id = NEW.listing_id
          AND id <> NEW.id AND status IN ('AWAITING_PAYMENT', 'PAYMENT_PENDING');
    END IF;

    -- Step 2: the release. The listing is archived now.
    IF NEW.released_at IS NOT NULL AND OLD.released_at IS NULL THEN
        IF NEW.listing_kind = 'SUBLESSOR_LISTING' THEN
            UPDATE sublessor_listings SET status = 'ARCHIVED', updated_at = CURRENT_TIMESTAMP
            WHERE id = NEW.listing_id AND status <> 'ARCHIVED';
        ELSE
            UPDATE listings SET status = 'ARCHIVED', updated_at = CURRENT_TIMESTAMP
            WHERE id = NEW.listing_id AND status <> 'ARCHIVED';
        END IF;
        UPDATE bookings SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP, version = version + 1
        WHERE listing_kind = NEW.listing_kind AND listing_id = NEW.listing_id
          AND id <> NEW.id AND status IN ('AWAITING_PAYMENT', 'PAYMENT_PENDING');
    END IF;
    RETURN NEW;
END; $$;

-- The trigger itself (bookings_archive_listing_after_move_in) already calls this function, so it is not recreated.
