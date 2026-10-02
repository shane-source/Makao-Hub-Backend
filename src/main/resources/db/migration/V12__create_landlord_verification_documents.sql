CREATE TABLE landlord_verification_documents
(
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    landlord_id   UUID         NOT NULL,
    document_type VARCHAR(30)  NOT NULL,
    object_path   VARCHAR(255) NOT NULL,
    content_type  VARCHAR(50)  NOT NULL,
    size_bytes    BIGINT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'UPLOADING',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT landlord_verification_documents_landlord_foreign_key
        FOREIGN KEY (landlord_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT landlord_verification_documents_type_check
        CHECK (document_type IN (
                                 'ID_DOCUMENT',
                                 'SELFIE',
                                 'OWNERSHIP_PROOF'
            )),

    CONSTRAINT landlord_verification_documents_status_check
        CHECK (status IN (
                          'UPLOADING',
                          'READY',
                          'REJECTED',
                          'DELETED'
            )),

    CONSTRAINT landlord_verification_documents_object_path_unique
        UNIQUE (object_path),

    CONSTRAINT landlord_verification_documents_size_check
        CHECK (size_bytes IS NULL OR size_bytes > 0)
);

CREATE UNIQUE INDEX landlord_verification_documents_active_type_unique
    ON landlord_verification_documents (landlord_id, document_type)
    WHERE status IN ('UPLOADING', 'READY');

CREATE INDEX landlord_verification_documents_landlord_index
    ON landlord_verification_documents (landlord_id);