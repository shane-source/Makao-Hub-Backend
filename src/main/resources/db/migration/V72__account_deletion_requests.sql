-- A request is not an account deletion. Retention and erasure require approved policy.
CREATE TABLE account_deletion_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'IN_REVIEW', 'CLOSED')),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX account_deletion_one_open_per_user
    ON account_deletion_requests(user_id) WHERE status IN ('REQUESTED', 'IN_REVIEW');
CREATE INDEX account_deletion_queue_order
    ON account_deletion_requests(requested_at, id) WHERE status = 'REQUESTED';
