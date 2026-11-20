-- Remembers which expiry notices were already sent so each grant notifies once per stage.
CREATE TABLE offer_expiry_notices (
    grant_id UUID NOT NULL,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('EXPIRING', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (grant_id, kind)
);
ALTER TABLE offer_expiry_notices ENABLE ROW LEVEL SECURITY;
