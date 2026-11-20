ALTER TABLE rental_applications
    ADD COLUMN IF NOT EXISTS move_in_date DATE,
    ADD COLUMN IF NOT EXISTS message VARCHAR(500);

ALTER TABLE sublessor_rental_applications
    ADD COLUMN IF NOT EXISTS move_in_date DATE,
    ADD COLUMN IF NOT EXISTS message VARCHAR(500);
