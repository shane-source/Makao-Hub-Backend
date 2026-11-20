-- The renter's own view of an application they submitted: archived (kept, moved to the Archived list)
-- or deleted (hidden for them only). The owner's side and the application itself are never changed.
CREATE TABLE renter_application_states
(
    kind           VARCHAR(10) NOT NULL,
    application_id UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    state          VARCHAR(10) NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (kind, application_id, user_id),

    CONSTRAINT renter_application_states_user_foreign_key
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT renter_application_states_kind_check
        CHECK (kind IN ('LANDLORD', 'SUBLESSOR')),
    CONSTRAINT renter_application_states_state_check
        CHECK (state IN ('ARCHIVED', 'DELETED'))
);

CREATE INDEX renter_application_states_user_index ON renter_application_states (user_id);
