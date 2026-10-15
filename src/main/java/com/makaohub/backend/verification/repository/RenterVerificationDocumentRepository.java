package com.makaohub.backend.verification.repository;

import com.makaohub.backend.verification.domain.RenterVerificationDocument;
import com.makaohub.backend.verification.domain.VerificationDocumentStatus;
import com.makaohub.backend.verification.domain.VerificationDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenterVerificationDocumentRepository
        extends JpaRepository<RenterVerificationDocument, UUID> {

    List<RenterVerificationDocument> findAllByRenterIdAndDocumentTypeInAndStatusIn(
            UUID renterId, Collection<VerificationDocumentType> types,
            Collection<VerificationDocumentStatus> statuses);

    Optional<RenterVerificationDocument>
    findByRenterIdAndDocumentTypeAndStatusIn(
            UUID renterId,
            VerificationDocumentType documentType,
            Collection<VerificationDocumentStatus> statuses
    );

    Optional<RenterVerificationDocument> findByIdAndRenterId(
            UUID id,
            UUID renterId
    );

    List<RenterVerificationDocument> findAllByRenterIdAndStatus(
            UUID renterId,
            VerificationDocumentStatus status
    );
}
