package com.makaohub.backend.verification.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.verification.service.LandlordVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/landlord/verification")
public class LandlordVerificationController {

    private final LandlordVerificationService verificationService;

    public LandlordVerificationController(
            LandlordVerificationService verificationService
    ) {
        this.verificationService = verificationService;
    }

    @GetMapping
    public LandlordVerificationResponse getMyVerification(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return verificationService.getMyVerification(
                extractUserId(jwt)
        );
    }

    @PostMapping("/documents/upload-url")
    public VerificationDocumentUploadUrlResponse
    createDocumentUploadUrl(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody
            VerificationDocumentUploadUrlRequest request
    ) {
        return verificationService.createDocumentUploadUrl(
                extractUserId(jwt),
                request.documentType(),
                request.contentType()
        );
    }

    @PostMapping("/documents/{documentId}/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmDocumentUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID documentId
    ) {
        verificationService.confirmDocumentUpload(
                extractUserId(jwt),
                documentId
        );
    }

    @PostMapping("/submit")
    public LandlordVerificationResponse submitMyVerification(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return verificationService.submitMyVerification(
                extractUserId(jwt)
        );
    }

    private UUID extractUserId(Jwt jwt) {
        String subject = jwt.getSubject();

        if (subject == null) {
            throw accountUnavailable();
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw accountUnavailable();
        }
    }

    private AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}