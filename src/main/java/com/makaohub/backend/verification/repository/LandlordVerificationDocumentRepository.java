package com.makaohub.backend.verification.repository;

import com.makaohub.backend.verification.domain.LandlordVerificationDocument;
import com.makaohub.backend.verification.domain.VerificationDocumentStatus;
import com.makaohub.backend.verification.domain.VerificationDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LandlordVerificationDocumentRepository
        extends JpaRepository<LandlordVerificationDocument, UUID> {

    Optional<LandlordVerificationDocument>
    findByLandlordIdAndDocumentTypeAndStatusIn(
            UUID landlordId,
            VerificationDocumentType documentType,
            Collection<VerificationDocumentStatus> statuses
    );

    Optional<LandlordVerificationDocument> findByIdAndLandlordId(
            UUID id,
            UUID landlordId
    );

    List<LandlordVerificationDocument> findAllByLandlordIdAndStatus(
            UUID landlordId,
            VerificationDocumentStatus status
    );
}