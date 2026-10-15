CREATE INDEX role_switch_challenges_user_issued_idx
    ON role_switch_challenges (user_id, issued_at);

-- No foreign key: audit events must survive account deletion.
CREATE TABLE role_switch_audit (
                                   id UUID PRIMARY KEY,

                                   user_id UUID NOT NULL,

                                   previous_role VARCHAR(40) NOT NULL
                                       CHECK (
                                           previous_role IN ('LANDLORD', 'RENTER', 'SUBLESSOR')
                                           ),

                                   selected_role VARCHAR(40) NOT NULL
                                       CHECK (
                                           selected_role IN ('LANDLORD', 'RENTER', 'SUBLESSOR')
                                           ),

                                   authentication_method VARCHAR(20) NOT NULL
                                       CHECK (
                                           authentication_method IN ('PASSWORD', 'GOOGLE')
                                           ),

                                   authorization_version BIGINT NOT NULL
                                       CHECK (authorization_version > 0),

                                   occurred_at TIMESTAMPTZ NOT NULL,

                                   CHECK (previous_role <> selected_role),

                                   UNIQUE (user_id, authorization_version)
);

CREATE FUNCTION protect_role_switch_audit()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Role switch audit is append-only';
END;
$$;

CREATE TRIGGER role_switch_audit_no_update_delete
    BEFORE UPDATE OR DELETE ON role_switch_audit
    FOR EACH ROW
EXECUTE FUNCTION protect_role_switch_audit();

CREATE TRIGGER role_switch_audit_no_truncate
    BEFORE TRUNCATE ON role_switch_audit
    FOR EACH STATEMENT
EXECUTE FUNCTION protect_role_switch_audit();

ALTER TABLE role_switch_audit ENABLE ROW LEVEL SECURITY;