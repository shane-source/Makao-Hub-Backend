CREATE TABLE google_identities (
                                   user_id UUID PRIMARY KEY,
                                   google_subject VARCHAR(255) NOT NULL UNIQUE,
                                   created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                   CONSTRAINT fk_google_identities_user
                                       FOREIGN KEY (user_id)
                                           REFERENCES users (id)
                                           ON DELETE CASCADE
);