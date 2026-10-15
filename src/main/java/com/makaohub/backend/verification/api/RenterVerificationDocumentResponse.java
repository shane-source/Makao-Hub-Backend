package com.makaohub.backend.verification.api;
import com.makaohub.backend.verification.domain.VerificationDocumentType;
import com.makaohub.backend.verification.domain.VerificationDocumentStatus;
import java.util.UUID;
/** Own metadata only, never storage paths, private URLs or document bytes. */
public record RenterVerificationDocumentResponse(UUID documentId, VerificationDocumentType documentType,
        VerificationDocumentStatus status, String contentType, Long sizeBytes, boolean securityChecked) {}
