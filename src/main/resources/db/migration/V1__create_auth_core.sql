CREATE TABLE users
(
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email             VARCHAR(320) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    phone_ciphertext  BYTEA,
    phone_lookup_hash CHAR(64),
    account_status    VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT users_account_status_check
        CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),

    CONSTRAINT users_phone_lookup_hash_unique
        UNIQUE (phone_lookup_hash)
);

CREATE UNIQUE INDEX users_email_lower_unique
    ON users (LOWER(email));


CREATE TABLE roles
(
    id   SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(40) NOT NULL UNIQUE,

    CONSTRAINT roles_name_check
        CHECK (name IN (
                        'LANDLORD',
                        'RENTER',
                        'ROOMMATE_SEEKER',
                        'ADMIN'
            ))
);

INSERT INTO roles (name)
VALUES ('LANDLORD'),
       ('RENTER'),
       ('ROOMMATE_SEEKER'),
       ('ADMIN');


CREATE TABLE user_roles
(
    user_id    UUID        NOT NULL,
    role_id    SMALLINT    NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    granted_by UUID,

    CONSTRAINT user_roles_primary_key
        PRIMARY KEY (user_id, role_id),

    CONSTRAINT user_roles_user_foreign_key
        FOREIGN KEY (user_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT user_roles_role_foreign_key
        FOREIGN KEY (role_id)
            REFERENCES roles (id)
            ON DELETE RESTRICT,

    CONSTRAINT user_roles_granted_by_foreign_key
        FOREIGN KEY (granted_by)
            REFERENCES users (id)
            ON DELETE SET NULL
);

CREATE INDEX user_roles_role_id_index
    ON user_roles (role_id);