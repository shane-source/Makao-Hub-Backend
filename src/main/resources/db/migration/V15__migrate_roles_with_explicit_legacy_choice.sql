-- Deploy with the previous application stopped; do not run old and new role models together.
ALTER TABLE users
    ADD COLUMN role_selection_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN authorization_version BIGINT NOT NULL DEFAULT 0
        CHECK (authorization_version >= 0);

CREATE TABLE role_selection_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    event VARCHAR(30) NOT NULL CHECK (event IN ('CHOICE_REQUIRED', 'ROLE_SELECTED')),
    previous_role VARCHAR(40) NOT NULL CHECK (previous_role = 'ROOMMATE_SEEKER'),
    selected_role VARCHAR(40),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((event = 'CHOICE_REQUIRED' AND selected_role IS NULL)
        OR (event = 'ROLE_SELECTED' AND selected_role IS NOT NULL
            AND selected_role IN ('RENTER', 'SUBLESSOR')))
);

UPDATE users u
SET role_selection_required = TRUE, authorization_version = authorization_version + 1
FROM user_roles ur JOIN roles r ON r.id = ur.role_id
WHERE ur.user_id = u.id AND r.name = 'ROOMMATE_SEEKER';

INSERT INTO role_selection_audit (user_id, event, previous_role)
SELECT id, 'CHOICE_REQUIRED', 'ROOMMATE_SEEKER'
FROM users WHERE role_selection_required;

UPDATE refresh_tokens t
SET revoked_at = CURRENT_TIMESTAMP, revocation_reason = 'ROLE_SELECTION_REQUIRED'
FROM users u
WHERE t.user_id = u.id AND u.role_selection_required AND t.revoked_at IS NULL;

DELETE FROM user_roles WHERE role_id IN (SELECT id FROM roles WHERE name = 'ROOMMATE_SEEKER');
DELETE FROM roles WHERE name = 'ROOMMATE_SEEKER';
ALTER TABLE roles DROP CONSTRAINT roles_name_check;
ALTER TABLE roles ADD CONSTRAINT roles_name_check
    CHECK (name IN ('LANDLORD', 'SUBLESSOR', 'RENTER', 'ADMIN'));
INSERT INTO roles (name) VALUES ('SUBLESSOR');

-- Preserve existing landlord review records, adding a role to their identity.
ALTER TABLE landlord_verifications RENAME TO role_verifications;
ALTER TABLE role_verifications ADD COLUMN role VARCHAR(40) NOT NULL DEFAULT 'LANDLORD';
ALTER TABLE role_verifications ALTER COLUMN role DROP DEFAULT;
ALTER TABLE role_verifications DROP CONSTRAINT landlord_verifications_pkey;
ALTER TABLE role_verifications ADD PRIMARY KEY (user_id, role);
ALTER TABLE role_verifications ADD CONSTRAINT role_verifications_role_fk
    FOREIGN KEY (role) REFERENCES roles(name);

INSERT INTO role_verifications (user_id, role)
SELECT ur.user_id, r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id
ON CONFLICT (user_id, role) DO NOTHING;

-- Every new assignment gets its own verification record. Never copy another role's status.
CREATE FUNCTION initialize_role_verification() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO role_verifications (user_id, role)
    SELECT NEW.user_id, name FROM roles WHERE id = NEW.role_id
    ON CONFLICT (user_id, role) DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER user_role_verification AFTER INSERT ON user_roles
    FOR EACH ROW EXECUTE FUNCTION initialize_role_verification();

CREATE FUNCTION record_legacy_role_choice() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE chosen_role VARCHAR(40);
BEGIN
    IF OLD.role_selection_required AND NOT NEW.role_selection_required THEN
        SELECT r.name INTO chosen_role FROM user_roles ur JOIN roles r ON r.id = ur.role_id
        WHERE ur.user_id = NEW.id;
        IF chosen_role IS NULL OR chosen_role NOT IN ('RENTER', 'SUBLESSOR')
            OR NEW.authorization_version <= OLD.authorization_version THEN
            RAISE EXCEPTION 'An explicit allowed role and token invalidation are required';
        END IF;
        INSERT INTO role_selection_audit (user_id, event, previous_role, selected_role)
        VALUES (NEW.id, 'ROLE_SELECTED', 'ROOMMATE_SEEKER', chosen_role);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER record_legacy_role_choice BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION record_legacy_role_choice();

CREATE FUNCTION protect_role_selection_audit() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Role selection audit is append-only';
END;
$$;
CREATE TRIGGER role_selection_audit_no_update_delete BEFORE UPDATE OR DELETE ON role_selection_audit
    FOR EACH ROW EXECUTE FUNCTION protect_role_selection_audit();
CREATE TRIGGER role_selection_audit_no_truncate BEFORE TRUNCATE ON role_selection_audit
    FOR EACH STATEMENT EXECUTE FUNCTION protect_role_selection_audit();
