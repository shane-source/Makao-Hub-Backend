-- Nullable for historical decisions. No reason is invented or backfilled.
ALTER TABLE sublessor_rental_applications ADD COLUMN rejection_reason VARCHAR(500);
