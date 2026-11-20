-- Refines V91. When an owner changes a listing's rent or deposit, each approved application for it is
-- removed from the renter's list (kept on record as withdrawn) instead of being sent back to review.
-- The renter applies again, which reopens the same application for the owner to approve at the new price.
CREATE TABLE IF NOT EXISTS superseded_applications
(
    kind           VARCHAR(10) NOT NULL CHECK (kind IN ('LANDLORD', 'SUBLESSOR')),
    application_id UUID        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (kind, application_id)
);

CREATE OR REPLACE FUNCTION landlord_listing_terms_changed() RETURNS trigger
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
    IF OLD.monthly_rent_kes IS DISTINCT FROM NEW.monthly_rent_kes
       OR OLD.deposit_kes IS DISTINCT FROM NEW.deposit_kes THEN
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
               'The host changed the price or deposit, so your approved application was removed. Apply again to book at the new price.'
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
           'The host changed the price or deposit, so your approved application was removed. Apply again to book at the new price.'
    FROM removed;
END; $$;
