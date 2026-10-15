CREATE TABLE role_switch_challenges (
                                        id UUID PRIMARY KEY,

                                        user_id UUID NOT NULL
                                            REFERENCES users(id) ON DELETE CASCADE,

                                        target_role VARCHAR(40) NOT NULL
                                            CHECK (
                                                target_role IN ('LANDLORD', 'RENTER', 'SUBLESSOR')
                                                ),

                                        authorization_version BIGINT NOT NULL
                                            CHECK (authorization_version >= 0),

                                        nonce_hash VARCHAR(64) NOT NULL
                                            CHECK (nonce_hash ~ '^[0-9a-f]{64}$'),

                                        issued_at TIMESTAMPTZ NOT NULL,

                                        expires_at TIMESTAMPTZ NOT NULL,

                                        consumed_at TIMESTAMPTZ,

                                        CONSTRAINT role_switch_challenge_valid_expiry
                                            CHECK (expires_at > issued_at),

                                        CONSTRAINT role_switch_challenge_valid_consumption
                                            CHECK (
                                                consumed_at IS NULL
                                                    OR (
                                                    consumed_at >= issued_at
                                                        AND consumed_at < expires_at
                                                    )
                                                )
);

ALTER TABLE role_switch_challenges ENABLE ROW LEVEL SECURITY;