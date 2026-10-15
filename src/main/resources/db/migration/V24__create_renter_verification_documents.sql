CREATE TABLE renter_verification_documents
(
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    renter_id     UUID         NOT NULL,
    document_type VARCHAR(30)  NOT NULL,
    object_path   VARCHAR(255) NOT NULL,
    content_type  VARCHAR(50)  NOT NULL,
    size_bytes    BIGINT,
    status        VARCHAR(20)  NOT NULL DEFAULT 'UPLOADING',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT renter_verification_documents_renter_foreign_key
        FOREIGN KEY (renter_id)
            REFERENCES users (id)
            ON DELETE CASCADE,

    CONSTRAINT renter_verification_documents_type_check
        CHECK (
            document_type IN (
                              'ID_FRONT',
                              'ID_BACK',
                              'SELFIE'
                )
            ),

    CONSTRAINT renter_verification_documents_status_check
        CHECK (
            status IN (
                       'UPLOADING',
                       'READY',
                       'REJECTED',
                       'DELETED'
                )
            ),

    CONSTRAINT renter_verification_documents_object_path_unique
        UNIQUE (object_path),

    CONSTRAINT renter_verification_documents_size_check
        CHECK (size_bytes IS NULL OR size_bytes > 0)
);

CREATE UNIQUE INDEX renter_verification_documents_active_type_unique
    ON renter_verification_documents (renter_id, document_type)
    WHERE status IN ('UPLOADING', 'READY');

CREATE INDEX renter_verification_documents_renter_index
    ON renter_verification_documents (renter_id);