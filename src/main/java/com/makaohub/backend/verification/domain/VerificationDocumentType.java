package com.makaohub.backend.verification.domain;

public enum VerificationDocumentType {

    /*
     * Kept for compatibility with existing database rows.
     * It is not accepted for new uploads.
     */
    ID_DOCUMENT,

    ID_FRONT,

    ID_BACK,

    SELFIE,

    OWNERSHIP_PROOF,

    RENTAL_AGREEMENT
}