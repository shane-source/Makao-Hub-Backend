-- Nullable for historical decisions. No rejection reason is invented or backfilled.
ALTER TABLE rental_applications ADD COLUMN rejection_reason VARCHAR(500);
