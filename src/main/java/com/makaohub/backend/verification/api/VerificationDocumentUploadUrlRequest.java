package com.makaohub.backend.verification.api;

import com.makaohub.backend.verification.domain.VerificationDocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Locale;

public record VerificationDocumentUploadUrlRequest(

        @NotNull(message = "Document type is required.")
        VerificationDocumentType documentType,

        @NotBlank(message = "Content type is required.")
        @Pattern(
                regexp = "(image/(jpeg|png|webp)|application/pdf)",
                message = "Documents must be JPEG, PNG, WebP, or PDF files."
        )
        String contentType,

        boolean replaceExisting

) {
    public VerificationDocumentUploadUrlRequest(VerificationDocumentType documentType, String contentType) {
        this(documentType, contentType, false);
    }
    public VerificationDocumentUploadUrlRequest {
        if (contentType != null) {
            contentType = contentType
                    .strip()
                    .toLowerCase(Locale.ROOT);
        }
    }
}
