ALTER TABLE user_roles
    ADD CONSTRAINT uq_user_roles_user_id
        UNIQUE (user_id);