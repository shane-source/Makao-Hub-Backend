CREATE TABLE legal_acceptances (
                                   id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                   user_id UUID NOT NULL,
                                   document_type VARCHAR(30) NOT NULL,
                                   document_version VARCHAR(50) NOT NULL,
                                   accepted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

                                   CONSTRAINT fk_legal_acceptances_user
                                       FOREIGN KEY (user_id)
                                           REFERENCES users (id)
                                           ON DELETE CASCADE,

                                   CONSTRAINT chk_legal_acceptances_type
                                       CHECK (
                                           document_type IN (
                                                             'TERMS_OF_SERVICE',
                                                             'PRIVACY_POLICY'
                                               )
                                           ),

                                   CONSTRAINT uq_legal_acceptances_document
                                       UNIQUE (
                                               user_id,
                                               document_type,
                                               document_version
                                           )
);

CREATE INDEX idx_legal_acceptances_user_id
    ON legal_acceptances (user_id);