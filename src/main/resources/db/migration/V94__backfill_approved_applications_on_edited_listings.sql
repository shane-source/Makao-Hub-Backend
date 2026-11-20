-- One-off cleanup for edits made before V91 to V93 were in place: an approved application whose listing
-- was changed after the approval is removed from the renter's list, exactly as the live rule does,
-- so the renter can apply again. Applications with a paid or active booking are left alone.

SELECT reset_unpaid_booking_terms('LANDLORD_LISTING', x.listing_id)
FROM (SELECT DISTINCT a.listing_id FROM rental_applications a
      JOIN listings l ON l.id = a.listing_id
      WHERE a.status = 'APPROVED' AND l.updated_at > a.updated_at) x;

WITH removed AS (
    UPDATE rental_applications a
    SET status = 'WITHDRAWN', updated_at = CURRENT_TIMESTAMP
    FROM listings l
    WHERE l.id = a.listing_id AND a.status = 'APPROVED' AND l.updated_at > a.updated_at
      AND NOT EXISTS (SELECT 1 FROM bookings b
                      WHERE b.listing_kind = 'LANDLORD_LISTING' AND b.source_application_id = a.id
                        AND b.status IN ('PAID_HELD', 'ACTIVE'))
    RETURNING a.id, a.renter_id),
marked AS (
    INSERT INTO superseded_applications(kind, application_id)
    SELECT 'LANDLORD', id FROM removed ON CONFLICT DO NOTHING RETURNING application_id)
INSERT INTO renter_application_states(kind, application_id, user_id, state)
SELECT 'LANDLORD', id, renter_id, 'DELETED' FROM removed
ON CONFLICT (kind, application_id, user_id) DO UPDATE SET state = 'DELETED', updated_at = CURRENT_TIMESTAMP;

SELECT reset_unpaid_booking_terms('SUBLESSOR_LISTING', x.listing_id)
FROM (SELECT DISTINCT a.sublessor_listing_id AS listing_id FROM sublessor_rental_applications a
      JOIN sublessor_listings l ON l.id = a.sublessor_listing_id
      WHERE a.status = 'APPROVED' AND l.updated_at > a.updated_at) x;

WITH removed AS (
    UPDATE sublessor_rental_applications a
    SET status = 'WITHDRAWN', updated_at = CURRENT_TIMESTAMP
    FROM sublessor_listings l
    WHERE l.id = a.sublessor_listing_id AND a.status = 'APPROVED' AND l.updated_at > a.updated_at
      AND NOT EXISTS (SELECT 1 FROM bookings b
                      WHERE b.listing_kind = 'SUBLESSOR_LISTING' AND b.source_application_id = a.id
                        AND b.status IN ('PAID_HELD', 'ACTIVE'))
    RETURNING a.id, a.renter_id),
marked AS (
    INSERT INTO superseded_applications(kind, application_id)
    SELECT 'SUBLESSOR', id FROM removed ON CONFLICT DO NOTHING RETURNING application_id)
INSERT INTO renter_application_states(kind, application_id, user_id, state)
SELECT 'SUBLESSOR', id, renter_id, 'DELETED' FROM removed
ON CONFLICT (kind, application_id, user_id) DO UPDATE SET state = 'DELETED', updated_at = CURRENT_TIMESTAMP;
