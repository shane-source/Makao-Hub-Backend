package com.makaohub.backend.verification.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.api.SublessorVerificationResponse;
import com.makaohub.backend.verification.api.SublessorVerificationDocumentResponse;
import com.makaohub.backend.verification.api.VerificationDocumentUploadUrlResponse;
import com.makaohub.backend.verification.domain.SublessorVerificationDocument;
import com.makaohub.backend.verification.domain.RoleVerification;
import com.makaohub.backend.verification.domain.VerificationDocumentStatus;
import com.makaohub.backend.verification.domain.VerificationDocumentType;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.SublessorVerificationDocumentRepository;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class SublessorVerificationService {

    private static final EnumSet<VerificationDocumentType>
            REQUIRED_DOCUMENT_TYPES = EnumSet.of(
            VerificationDocumentType.ID_FRONT,
            VerificationDocumentType.ID_BACK,
            VerificationDocumentType.SELFIE,
            VerificationDocumentType.RENTAL_AGREEMENT
    );

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleVerificationRepository verificationRepository;
    private final SublessorVerificationDocumentRepository documentRepository;
    private final SupabaseStorageClient storageClient;
    private final UploadSecurityProperties uploadLimits;
    private final OwnershipPdfValidator pdfValidator;
    private final VerificationImageValidator imageValidator;

    public SublessorVerificationService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleVerificationRepository verificationRepository,
            SublessorVerificationDocumentRepository documentRepository,
            SupabaseStorageClient storageClient,
            UploadSecurityProperties uploadLimits,
            OwnershipPdfValidator pdfValidator,
            VerificationImageValidator imageValidator
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.verificationRepository = verificationRepository;
        this.documentRepository = documentRepository;
        this.storageClient = storageClient;
        this.uploadLimits = uploadLimits;
        this.pdfValidator = pdfValidator;
        this.imageValidator = imageValidator;
    }

    @Transactional(readOnly = true)
    public SublessorVerificationResponse getMyVerification(
            UUID authenticatedUserId
    ) {
        requireActiveSublessor(authenticatedUserId);

        return verificationRepository
                .findByUserIdAndRole(
                        authenticatedUserId,
                        RoleName.SUBLESSOR
                )
                .map(this::toResponse)
                .orElseGet(() -> new SublessorVerificationResponse(
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
            String contentType,
            boolean replaceExisting
    ) {
        lockActiveSublessor(authenticatedUserId);
        requireEditableVerification(authenticatedUserId);

        if (!REQUIRED_DOCUMENT_TYPES.contains(documentType)
                || (documentType == VerificationDocumentType.RENTAL_AGREEMENT
                    ? !"application/pdf".equals(contentType)
                    : !List.of("image/jpeg", "image/png").contains(contentType))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Rental agreement must be a PDF; ID front, ID back and selfie must be images.");
        }

        SublessorVerificationDocument document =
                documentRepository
                        .findBySublessorIdAndDocumentTypeAndStatusIn(
                                authenticatedUserId,
                                documentType,
                                List.of(
                                        VerificationDocumentStatus.UPLOADING,
                                        VerificationDocumentStatus.READY
                                )
                        )
                        .orElse(null);

        if (replaceExisting && document != null) {
            // Explicit owner replacement only; preserve prior bytes/records, never silently migrate them.
            document.supersede();
            documentRepository.saveAndFlush(document); // Release the active-type unique index before insert.
            document = null;
        }
        if (document == null) {
            document = documentRepository.save(
                                new SublessorVerificationDocument(
                                        authenticatedUserId,
                                        documentType,
                                        buildObjectPath(
                                                authenticatedUserId,
                                                documentType,
                                                contentType
                                        ),
                                        contentType
                                )
                        );
        }

        if (!document.getContentType().equals(contentType)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The existing upload has a different format. Explicitly replace it to start a new file.");
        }

        if (document.getStatus()
                == VerificationDocumentStatus.READY) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A confirmed document already exists for this evidence type."
            );
        }

        SupabaseStorageClient.SignedUploadUrl signedUpload =
                storageClient
                        .createSignedEvidenceUploadUrl(
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
    public VerificationDocumentUploadUrlResponse createDocumentUploadUrl(UUID userId,
            VerificationDocumentType type, String contentType) {
        return createDocumentUploadUrl(userId, type, contentType, false);
    }

    @Transactional(readOnly = true)
    public List<SublessorVerificationDocumentResponse> getMyDocuments(UUID authenticatedUserId) {
        requireActiveSublessor(authenticatedUserId);
        // The caller cannot select another sublessor. Four required evidence types keep this list bounded.
        return documentRepository.findAllBySublessorIdAndDocumentTypeInAndStatusIn(
                authenticatedUserId, REQUIRED_DOCUMENT_TYPES,
                List.of(VerificationDocumentStatus.UPLOADING, VerificationDocumentStatus.READY)
        ).stream().map(document -> new SublessorVerificationDocumentResponse(
                document.getId(), document.getDocumentType(), document.getStatus(), document.getContentType(), document.getSizeBytes(), document.hasCurrentSecurityChecks()
        )).toList();
    }

    @Transactional
    public void confirmDocumentUpload(
            UUID authenticatedUserId,
            UUID documentId
    ) {
        lockActiveSublessor(authenticatedUserId);
        requireEditableVerification(authenticatedUserId);

        SublessorVerificationDocument document =
                documentRepository.findByIdAndSublessorId(
                        documentId,
                        authenticatedUserId
                ).orElseThrow(
                        SublessorVerificationService::invalidDocumentUpload
                );

        if (document.getStatus()
                != VerificationDocumentStatus.UPLOADING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This document has already been confirmed."
            );
        }

        if (!document.usesImmutableEvidencePath()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This older upload must be explicitly replaced before the current security checks can be confirmed.");
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

        byte[] bytes = storageClient.readVerificationDocument(document.getObjectPath(), objectInfo.sizeBytes());
        if (bytes == null || bytes.length != objectInfo.sizeBytes()) throw invalidDocumentUpload();
        try {
            if (document.getDocumentType() == VerificationDocumentType.RENTAL_AGREEMENT) {
                if (!"application/pdf".equals(document.getContentType())) throw invalidDocumentUpload();
                pdfValidator.validate(bytes);
                document.markReady(bytes.length);
            } else {
                byte[] prepared = imageValidator.prepare(bytes, document.getContentType());
                try {
                    if (prepared == null || prepared.length == 0 || prepared.length > uploadLimits.verificationDocumentMaxBytes())
                        throw invalidDocumentUpload();
                    storageClient.storePreparedVerificationImage(document.getObjectPath(), document.getContentType(), prepared);
                    document.markReady(prepared.length);
                } finally { if (prepared != null) java.util.Arrays.fill(prepared, (byte) 0); }
            }
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    @Transactional
    public SublessorVerificationResponse submitMyVerification(
            UUID authenticatedUserId
    ) {
        lockActiveSublessor(authenticatedUserId);

        EnumSet<VerificationDocumentType> readyDocumentTypes =
                EnumSet.noneOf(VerificationDocumentType.class);

        List<SublessorVerificationDocument> readyDocuments = documentRepository.findAllBySublessorIdAndStatus(
                authenticatedUserId,
                VerificationDocumentStatus.READY
        );
        readyDocuments.stream().filter(SublessorVerificationDocument::hasCurrentSecurityChecks)
                .forEach(document -> readyDocumentTypes.add(document.getDocumentType()));

        if (readyDocuments.stream().noneMatch(document -> document.hasCurrentSecurityChecks()
                && document.getDocumentType() == VerificationDocumentType.RENTAL_AGREEMENT
                && "application/pdf".equals(document.getContentType()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirm a PDF rental agreement before submitting.");
        }

        if (!readyDocumentTypes.containsAll(
                REQUIRED_DOCUMENT_TYPES
        )) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Confirm the front and back of your ID, "
                            + "selfie, and rental agreement before submitting."
            );
        }

        RoleVerification verification =
                verificationRepository
                        .findByUserIdAndRole(
                                authenticatedUserId,
                                RoleName.SUBLESSOR
                        )
                        .orElseGet(() -> new RoleVerification(
                                authenticatedUserId,
                                RoleName.SUBLESSOR
                        ));

        if (verification.getStatus()
                == VerificationStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This verification is already under review."
            );
        }

        if (verification.getStatus()
                == VerificationStatus.VERIFIED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This sublessor is already verified."
            );
        }

        verification.markPending();

        return toResponse(
                verificationRepository.save(verification)
        );
    }

    private String buildObjectPath(
            UUID sublessorId,
            VerificationDocumentType documentType,
            String contentType
    ) {
        return sublessorId
                + "/sublessor-evidence-v1/"
                + documentType.name().toLowerCase(Locale.ROOT)
                + "/"
                + UUID.randomUUID()
                + extensionFor(contentType);
    }

    private void requireActiveSublessor(
            UUID authenticatedUserId
    ) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(
                        SublessorVerificationService::accountUnavailable
                );

        if (user.getAccountStatus()
                != AccountStatus.ACTIVE) {
            throw accountUnavailable();
        }

        if (!userRoleRepository.existsByUser_IdAndRole_Name(
                authenticatedUserId,
                RoleName.SUBLESSOR
        )) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only sublessors can access sublessor verification."
            );
        }
    }

    private void lockActiveSublessor(UUID userId) {
        userRepository.findByIdForUpdate(userId).orElseThrow(SublessorVerificationService::accountUnavailable);
        requireActiveSublessor(userId);
    }

    private void requireEditableVerification(UUID userId) {
        verificationRepository.findByUserIdAndRole(userId, RoleName.SUBLESSOR).ifPresent(verification -> {
            if (verification.getStatus() == VerificationStatus.PENDING || verification.getStatus() == VerificationStatus.VERIFIED) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Evidence cannot change while under review or after approval.");
            }
        });
    }

    private SublessorVerificationResponse toResponse(
            RoleVerification verification
    ) {
        return new SublessorVerificationResponse(
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
            case "application/pdf" -> ".pdf";
            default -> throw invalidDocumentUpload();
        };
    }

    private static ResponseStatusException invalidDocumentUpload() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "The verification document upload is invalid or unavailable."
        );
    }

    private static ResponseStatusException accountUnavailable() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "The account is unavailable."
        );
    }
}
