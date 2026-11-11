CREATE TABLE application_owner_notes (
    landlord_application_id UUID UNIQUE REFERENCES rental_applications(id) ON DELETE CASCADE,
    sublessor_application_id UUID UNIQUE REFERENCES sublessor_rental_applications(id) ON DELETE CASCADE,
    hidden BOOLEAN NOT NULL DEFAULT FALSE,
    remark VARCHAR(500),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((landlord_application_id IS NOT NULL) <> (sublessor_application_id IS NOT NULL))
);
