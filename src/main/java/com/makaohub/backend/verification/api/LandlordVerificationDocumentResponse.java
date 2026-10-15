package com.makaohub.backend.verification.api;

import com.makaohub.backend.verification.domain.VerificationDocumentStatus;
import com.makaohub.backend.verification.domain.VerificationDocumentType;
import java.util.UUID;

/** Readiness metadata only: no document contents, storage paths or signed read URLs. */
public record LandlordVerificationDocumentResponse(
        UUID documentId,
        VerificationDocumentType documentType,
        VerificationDocumentStatus status,
        String contentType,
        Long sizeBytes,
        boolean securityChecked
) { }
