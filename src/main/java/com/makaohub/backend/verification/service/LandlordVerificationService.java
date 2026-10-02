package com.makaohub.backend.verification.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.api.LandlordVerificationResponse;
import com.makaohub.backend.verification.api.VerificationDocumentUploadUrlResponse;
import com.makaohub.backend.verification.domain.LandlordVerification;
import com.makaohub.backend.verification.domain.LandlordVerificationDocument;
import com.makaohub.backend.verification.domain.VerificationDocumentStatus;
import com.makaohub.backend.verification.domain.VerificationDocumentType;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.LandlordVerificationDocumentRepository;
import com.makaohub.backend.verification.repository.LandlordVerificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class LandlordVerificationService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final LandlordVerificationRepository verificationRepository;
    private final LandlordVerificationDocumentRepository documentRepository;
    private final SupabaseStorageClient storageClient;
    private final UploadSecurityProperties uploadLimits;

    public LandlordVerificationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            LandlordVerificationRepository verificationRepository,
            LandlordVerificationDocumentRepository documentRepository,
            SupabaseStorageClient storageClient,
            UploadSecurityProperties uploadLimits
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.verificationRepository = verificationRepository;
        this.documentRepository = documentRepository;
        this.storageClient = storageClient;
        this.uploadLimits = uploadLimits;
    }

    @Transactional(readOnly = true)
    public LandlordVerificationResponse getMyVerification(
            UUID authenticatedUserId
    ) {
        requireActiveLandlord(authenticatedUserId);

        return verificationRepository.findById(authenticatedUserId)
                .map(this::toResponse)
                .orElseGet(() -> new LandlordVerificationResponse(
                        VerificationStatus.NOT_STARTED,
                        null,
                        null,
                        null
                ));
    }

    @Transactional
    public VerificationDocumentUploadUrlResponse createDocumentUploadUrl(
            UUID authenticatedUserId,
            VerificationDocumentType documentType,
            String contentType
    ) {
        requireActiveLandlord(authenticatedUserId);

        LandlordVerificationDocument document =
                documentRepository
                        .findByLandlordIdAndDocumentTypeAndStatusIn(
                                authenticatedUserId,
                                documentType,
                                List.of(
                                        VerificationDocumentStatus.UPLOADING,
                                        VerificationDocumentStatus.READY
                                )
                        )
                        .orElseGet(() -> documentRepository.save(
                                new LandlordVerificationDocument(
                                        authenticatedUserId,
                                        documentType,
                                        buildObjectPath(
                                                authenticatedUserId,
                                                documentType,
                                                contentType
                                        ),
                                        contentType
                                )
                        ));

        if (document.getStatus() == VerificationDocumentStatus.READY) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A confirmed document already exists for this evidence type."
            );
        }

        SupabaseStorageClient.SignedUploadUrl signedUpload =
                storageClient.createSignedVerificationDocumentUploadUrl(
                        document.getObjectPath()
                );

        return new VerificationDocumentUploadUrlResponse(
                document.getId(),
                document.getDocumentType(),
                document.getContentType(),
                signedUpload.uploadUrl()
        );
    }

    @Transactional
    public void confirmDocumentUpload(
            UUID authenticatedUserId,
            UUID documentId
    ) {
        requireActiveLandlord(authenticatedUserId);

        LandlordVerificationDocument document =
                documentRepository.findByIdAndLandlordId(
                        documentId,
                        authenticatedUserId
                ).orElseThrow(
                        LandlordVerificationService::invalidDocumentUpload
                );

        if (document.getStatus() != VerificationDocumentStatus.UPLOADING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This document has already been confirmed."
            );
        }

        SupabaseStorageClient.StoredObjectInfo objectInfo =
                storageClient.getVerificationDocumentInfo(
                        document.getObjectPath()
                );

        if (objectInfo == null
                || objectInfo.sizeBytes() <= 0
                || objectInfo.sizeBytes()
                > uploadLimits.verificationDocumentMaxBytes()
                || !document.getContentType().equals(
                objectInfo.contentType()
        )) {
            throw invalidDocumentUpload();
        }

        document.markReady(objectInfo.sizeBytes());
    }

    @Transactional
    public LandlordVerificationResponse submitMyVerification(
            UUID authenticatedUserId
    ) {
        requireActiveLandlord(authenticatedUserId);

        EnumSet<VerificationDocumentType> readyDocumentTypes =
                EnumSet.noneOf(VerificationDocumentType.class);

        documentRepository.findAllByLandlordIdAndStatus(
                authenticatedUserId,
                VerificationDocumentStatus.READY
        ).forEach(document -> readyDocumentTypes.add(
                document.getDocumentType()
        ));

        if (!readyDocumentTypes.containsAll(
                EnumSet.allOf(VerificationDocumentType.class)
        )) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Confirm an ID document, selfie, and ownership proof before submitting."
            );
        }

        LandlordVerification verification =
                verificationRepository.findById(authenticatedUserId)
                        .orElseGet(() -> new LandlordVerification(
                                authenticatedUserId
                        ));

        if (verification.getStatus() == VerificationStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This verification is already under review."
            );
        }

        if (verification.getStatus() == VerificationStatus.VERIFIED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This landlord is already verified."
            );
        }

        verification.markPending();

        return toResponse(verificationRepository.save(verification));
    }

    private String buildObjectPath(
            UUID landlordId,
            VerificationDocumentType documentType,
            String contentType
    ) {
        return landlordId
                + "/"
                + documentType.name().toLowerCase(Locale.ROOT)
                + "/"
                + UUID.randomUUID()
                + extensionFor(contentType);
    }

    private void requireActiveLandlord(UUID authenticatedUserId) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(LandlordVerificationService::accountUnavailable);

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw accountUnavailable();
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                authenticatedUserId,
                RoleName.LANDLORD
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only landlords can access landlord verification."
            );
        }
    }

    private LandlordVerificationResponse toResponse(
            LandlordVerification verification
    ) {
        return new LandlordVerificationResponse(
                verification.getStatus(),
                verification.getSubmittedAt(),
                verification.getReviewedAt(),
                verification.getRejectionReason()
        );
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw invalidDocumentUpload();
        };
    }

    private static ResponseStatusException invalidDocumentUpload() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The verification document upload is invalid or unavailable."
        );
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}