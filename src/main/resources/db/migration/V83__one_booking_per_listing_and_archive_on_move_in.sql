-- 1. A listing can have only one booking that is paying, paid or active at a time.
--    The application takes a row lock first (ListingBookingLock); this index is the backstop.
--    If old data already breaks the rule, skip the index instead of failing the whole migration.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM bookings
        WHERE status IN ('PAYMENT_PENDING', 'PAID_HELD', 'ACTIVE')
        GROUP BY listing_kind, listing_id HAVING count(*) > 1
    ) THEN
        RAISE WARNING 'bookings_one_live_per_listing NOT created: some listings already have more than one live booking. Resolve them and create the index by hand.';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS bookings_one_live_per_listing
            ON bookings (listing_kind, listing_id)
            WHERE status IN ('PAYMENT_PENDING', 'PAID_HELD', 'ACTIVE');
    END IF;
END $$;

-- 2. When the renter answers True (the owner has already reported move-in), the listing leaves the
--    marketplace and any other unpaid bookings for it are cancelled. Done in the database so it
--    happens no matter which service recorded the answer.
CREATE OR REPLACE FUNCTION archive_listing_after_move_in() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF NEW.move_in_accepted IS TRUE AND NEW.owner_move_in_confirmed_at IS NOT NULL
       AND (OLD.move_in_accepted IS DISTINCT FROM TRUE) THEN
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

DROP TRIGGER IF EXISTS bookings_archive_listing_after_move_in ON bookings;
CREATE TRIGGER bookings_archive_listing_after_move_in
    AFTER UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION archive_listing_after_move_in();

-- 3. Booking terms are accepted by paying/accepting the booking itself, so there is no separate
--    "confirm terms" step. Mark both sides as agreed the moment a booking is paid and held.
CREATE OR REPLACE FUNCTION auto_agree_when_paid() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('PAID_HELD', 'ACTIVE') THEN
        NEW.renter_agreed_at := COALESCE(NEW.renter_agreed_at, OLD.renter_agreed_at, CURRENT_TIMESTAMP);
        NEW.owner_agreed_at  := COALESCE(NEW.owner_agreed_at,  OLD.owner_agreed_at,  CURRENT_TIMESTAMP);
    END IF;
    RETURN NEW;
END; $$;

DROP TRIGGER IF EXISTS bookings_auto_agree_when_paid ON bookings;
CREATE TRIGGER bookings_auto_agree_when_paid
    BEFORE UPDATE ON bookings
    FOR EACH ROW EXECUTE FUNCTION auto_agree_when_paid();

-- Bookings that are already held get the same treatment.
UPDATE bookings
SET renter_agreed_at = COALESCE(renter_agreed_at, CURRENT_TIMESTAMP),
    owner_agreed_at  = COALESCE(owner_agreed_at,  CURRENT_TIMESTAMP)
WHERE status = 'PAID_HELD' AND (renter_agreed_at IS NULL OR owner_agreed_at IS NULL);
