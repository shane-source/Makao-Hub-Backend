-- When an owner changes a listing's rent or deposit:
--   1. Every approved application for it goes back to review, so the owner approves again at the new terms.
--   2. Bookings that are still unpaid are dropped (and their open payment attempts expired), so the next
--      booking and payment are built from the new price. Paid or active bookings are never touched.
-- Done in the database so it holds whichever service or screen made the edit.

CREATE OR REPLACE FUNCTION reset_unpaid_booking_terms(p_kind VARCHAR, p_listing UUID) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    UPDATE payment_intents pi
    SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP, version = version + 1
    WHERE pi.payment_type = 'BOOKING' AND pi.status IN ('CREATED', 'PENDING')
      AND pi.booking_id IN (
          SELECT b.id FROM bookings b
          WHERE b.listing_kind = p_kind AND b.listing_id = p_listing
            AND b.status IN ('AWAITING_PAYMENT', 'PAYMENT_PENDING'));

    DELETE FROM bookings b
    WHERE b.listing_kind = p_kind AND b.listing_id = p_listing
      AND b.status IN ('AWAITING_PAYMENT', 'PAYMENT_PENDING')
      AND NOT EXISTS (SELECT 1 FROM ledger_entries le WHERE le.booking_id = b.id)
      AND NOT EXISTS (SELECT 1 FROM payment_intents pi
                      WHERE pi.booking_id = b.id AND pi.status IN ('SUCCEEDED', 'REFUND_PENDING', 'REFUNDED', 'DISPUTED'));
END; $$;

CREATE OR REPLACE FUNCTION landlord_listing_terms_changed() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF OLD.monthly_rent_kes IS DISTINCT FROM NEW.monthly_rent_kes
       OR OLD.deposit_kes IS DISTINCT FROM NEW.deposit_kes THEN
        PERFORM reset_unpaid_booking_terms('LANDLORD_LISTING', NEW.id);
        WITH reopened AS (
            UPDATE rental_applications a
            SET status = 'UNDER_REVIEW', updated_at = CURRENT_TIMESTAMP
            WHERE a.listing_id = NEW.id AND a.status = 'APPROVED'
              AND NOT EXISTS (SELECT 1 FROM bookings b
                              WHERE b.listing_kind = 'LANDLORD_LISTING' AND b.source_application_id = a.id
                                AND b.status IN ('PAID_HELD', 'ACTIVE'))
            RETURNING a.renter_id)
        INSERT INTO notifications(user_id, type, title, body)
        SELECT renter_id, 'APPLICATION_UNDER_REVIEW', 'Listing updated',
               'The host changed the price or deposit, so your application is back under review. You can book once it is approved again.'
        FROM reopened;
    END IF;
    RETURN NEW;
END; $$;

DROP TRIGGER IF EXISTS listings_terms_changed ON listings;
CREATE TRIGGER listings_terms_changed
    AFTER UPDATE ON listings
    FOR EACH ROW EXECUTE FUNCTION landlord_listing_terms_changed();

CREATE OR REPLACE FUNCTION sublessor_listing_reopen_applications(p_listing UUID) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    PERFORM reset_unpaid_booking_terms('SUBLESSOR_LISTING', p_listing);
    WITH reopened AS (
        UPDATE sublessor_rental_applications a
        SET status = 'UNDER_REVIEW', updated_at = CURRENT_TIMESTAMP
        WHERE a.sublessor_listing_id = p_listing AND a.status = 'APPROVED'
          AND NOT EXISTS (SELECT 1 FROM bookings b
                          WHERE b.listing_kind = 'SUBLESSOR_LISTING' AND b.source_application_id = a.id
                            AND b.status IN ('PAID_HELD', 'ACTIVE'))
        RETURNING a.renter_id)
    INSERT INTO notifications(user_id, type, title, body)
    SELECT renter_id, 'APPLICATION_UNDER_REVIEW', 'Listing updated',
           'The host changed the price or deposit, so your application is back under review. You can book once it is approved again.'
    FROM reopened;
END; $$;

CREATE OR REPLACE FUNCTION sublessor_listing_rent_changed() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF OLD.monthly_rent_kes IS DISTINCT FROM NEW.monthly_rent_kes THEN
        PERFORM sublessor_listing_reopen_applications(NEW.id);
    END IF;
    RETURN NEW;
END; $$;

DROP TRIGGER IF EXISTS sublessor_listings_terms_changed ON sublessor_listings;
CREATE TRIGGER sublessor_listings_terms_changed
    AFTER UPDATE ON sublessor_listings
    FOR EACH ROW EXECUTE FUNCTION sublessor_listing_rent_changed();

CREATE OR REPLACE FUNCTION sublessor_household_deposit_changed() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF OLD.deposit_kes IS DISTINCT FROM NEW.deposit_kes THEN
        PERFORM sublessor_listing_reopen_applications(NEW.listing_id);
    END IF;
    RETURN NEW;
END; $$;

DROP TRIGGER IF EXISTS sublessor_household_terms_changed ON sublessor_household_details;
CREATE TRIGGER sublessor_household_terms_changed
    AFTER UPDATE ON sublessor_household_details
    FOR EACH ROW EXECUTE FUNCTION sublessor_household_deposit_changed();
