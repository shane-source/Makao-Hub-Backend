package com.makaohub.backend.verification.api;

import com.makaohub.backend.verification.domain.VerificationDocumentType;

import java.util.UUID;

public record VerificationDocumentUploadUrlResponse(
        UUID documentId,
        VerificationDocumentType documentType,
        String contentType,
        String uploadUrl
) {
}