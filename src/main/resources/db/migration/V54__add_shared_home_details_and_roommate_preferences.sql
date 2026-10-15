-- New empty tables only. Existing listings/users are neither backfilled nor reclassified.
CREATE TABLE sublessor_household_details (
    listing_id UUID PRIMARY KEY REFERENCES sublessor_listings(id) ON DELETE CASCADE,
    deposit_kes BIGINT CHECK (deposit_kes IS NULL OR deposit_kes >= 0),
    current_household VARCHAR(600),
    total_occupants INTEGER CHECK (total_occupants IS NULL OR total_occupants BETWEEN 1 AND 20),
    people_per_room INTEGER CHECK (people_per_room IS NULL OR people_per_room BETWEEN 1 AND 10),
    lifestyle VARCHAR(600),
    amenities TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE roommate_preferences (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(40) NOT NULL CHECK (role IN ('RENTER', 'SUBLESSOR')),
    quiet_social VARCHAR(20) NOT NULL CHECK (quiet_social IN ('QUIET','SOCIAL','BALANCED')),
    sleep_schedule VARCHAR(20) NOT NULL CHECK (sleep_schedule IN ('EARLY','NIGHT','FLEXIBLE')),
    cleanliness VARCHAR(20) NOT NULL CHECK (cleanliness IN ('DAILY','WEEKLY')),
    cooking VARCHAR(20) NOT NULL CHECK (cooking IN ('REGULAR','OCCASIONAL','NONE')),
    guests VARCHAR(20) NOT NULL CHECK (guests IN ('ASK_FIRST','OCCASIONAL','FREQUENT')),
    smoking VARCHAR(20) NOT NULL CHECK (smoking IN ('NO_INDOOR','OUTSIDE_ONLY','SMOKER')),
    pets VARCHAR(20) NOT NULL CHECK (pets IN ('NONE','PETS_OK','HAS_PETS')),
    budget_min_kes BIGINT NOT NULL CHECK (budget_min_kes > 0),
    budget_max_kes BIGINT NOT NULL CHECK (budget_max_kes >= budget_min_kes),
    discovery_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    area_label VARCHAR(150),
    about VARCHAR(600),
    employment VARCHAR(180),
    move_in_date DATE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, role)
);
CREATE INDEX roommate_preferences_discovery_idx ON roommate_preferences(user_id)
    WHERE role = 'RENTER' AND discovery_opt_in;

CREATE TABLE roommate_preference_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(40) NOT NULL,
    discovery_opt_in BOOLEAN NOT NULL,
    event VARCHAR(30) NOT NULL CHECK (event = 'PREFERENCES_SAVED'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE FUNCTION reject_roommate_preference_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Preference audit records are append-only';
END;
$$;
CREATE TRIGGER roommate_preference_audit_append_only BEFORE UPDATE OR DELETE ON roommate_preference_audit
FOR EACH ROW EXECUTE FUNCTION reject_roommate_preference_audit_mutation();
