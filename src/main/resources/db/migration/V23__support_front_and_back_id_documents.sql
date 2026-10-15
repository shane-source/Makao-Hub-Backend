ALTER TABLE landlord_verification_documents
    DROP CONSTRAINT landlord_verification_documents_type_check;

ALTER TABLE landlord_verification_documents
    ADD CONSTRAINT landlord_verification_documents_type_check
        CHECK (
            document_type IN (
                              'ID_DOCUMENT',
                              'ID_FRONT',
                              'ID_BACK',
                              'SELFIE',
                              'OWNERSHIP_PROOF'
                )
            );