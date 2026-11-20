-- Refines V92: any change to a listing's details (not only price) removes the renter's approved
-- application so the owner approves again. Status, views and boost changes do not count.
CREATE OR REPLACE FUNCTION landlord_listing_terms_changed() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF (OLD.title, OLD.description, OLD.property_type, OLD.monthly_rent_kes, OLD.bedroom_count, OLD.bathroom_count,
        OLD.furnished, OLD.available_from, OLD.deposit_kes, OLD.water_available, OLD.security_available,
        OLD.parking_available, OLD.balcony, OLD.pets_allowed)
       IS DISTINCT FROM
       (NEW.title, NEW.description, NEW.property_type, NEW.monthly_rent_kes, NEW.bedroom_count, NEW.bathroom_count,
        NEW.furnished, NEW.available_from, NEW.deposit_kes, NEW.water_available, NEW.security_available,
        NEW.parking_available, NEW.balcony, NEW.pets_allowed) THEN
        PERFORM reset_unpaid_booking_terms('LANDLORD_LISTING', NEW.id);
        WITH removed AS (
            UPDATE rental_applications a
            SET status = 'WITHDRAWN', updated_at = CURRENT_TIMESTAMP
            WHERE a.listing_id = NEW.id AND a.status = 'APPROVED'
              AND NOT EXISTS (SELECT 1 FROM bookings b
                              WHERE b.listing_kind = 'LANDLORD_LISTING' AND b.source_application_id = a.id
                                AND b.status IN ('PAID_HELD', 'ACTIVE'))
            RETURNING a.id, a.renter_id),
        marked AS (
            INSERT INTO superseded_applications(kind, application_id)
            SELECT 'LANDLORD', id FROM removed ON CONFLICT DO NOTHING RETURNING application_id),
        hidden AS (
            INSERT INTO renter_application_states(kind, application_id, user_id, state)
            SELECT 'LANDLORD', id, renter_id, 'DELETED' FROM removed
            ON CONFLICT (kind, application_id, user_id) DO UPDATE SET state = 'DELETED', updated_at = CURRENT_TIMESTAMP
            RETURNING application_id)
        INSERT INTO notifications(user_id, type, title, body)
        SELECT renter_id, 'APPLICATION_UNDER_REVIEW', 'Listing updated',
               'The host updated this listing, so your approved application was removed. Apply again to book at the current terms.'
        FROM removed;
    END IF;
    RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION sublessor_listing_reopen_applications(p_listing UUID) RETURNS void
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    PERFORM reset_unpaid_booking_terms('SUBLESSOR_LISTING', p_listing);
    WITH removed AS (
        UPDATE sublessor_rental_applications a
        SET status = 'WITHDRAWN', updated_at = CURRENT_TIMESTAMP
        WHERE a.sublessor_listing_id = p_listing AND a.status = 'APPROVED'
          AND NOT EXISTS (SELECT 1 FROM bookings b
                          WHERE b.listing_kind = 'SUBLESSOR_LISTING' AND b.source_application_id = a.id
                            AND b.status IN ('PAID_HELD', 'ACTIVE'))
        RETURNING a.id, a.renter_id),
    marked AS (
        INSERT INTO superseded_applications(kind, application_id)
        SELECT 'SUBLESSOR', id FROM removed ON CONFLICT DO NOTHING RETURNING application_id),
    hidden AS (
        INSERT INTO renter_application_states(kind, application_id, user_id, state)
        SELECT 'SUBLESSOR', id, renter_id, 'DELETED' FROM removed
        ON CONFLICT (kind, application_id, user_id) DO UPDATE SET state = 'DELETED', updated_at = CURRENT_TIMESTAMP
        RETURNING application_id)
    INSERT INTO notifications(user_id, type, title, body)
    SELECT renter_id, 'APPLICATION_UNDER_REVIEW', 'Listing updated',
           'The host updated this listing, so your approved application was removed. Apply again to book at the current terms.'
    FROM removed;
END; $$;

CREATE OR REPLACE FUNCTION sublessor_listing_rent_changed() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF (OLD.title, OLD.description, OLD.space_type, OLD.monthly_rent_kes, OLD.bathroom_count, OLD.furnished, OLD.available_from)
       IS DISTINCT FROM
       (NEW.title, NEW.description, NEW.space_type, NEW.monthly_rent_kes, NEW.bathroom_count, NEW.furnished, NEW.available_from) THEN
        PERFORM sublessor_listing_reopen_applications(NEW.id);
    END IF;
    RETURN NEW;
END; $$;
