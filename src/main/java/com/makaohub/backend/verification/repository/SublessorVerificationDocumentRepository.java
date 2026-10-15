package com.makaohub.backend.verification.repository;

import com.makaohub.backend.verification.domain.SublessorVerificationDocument;
import com.makaohub.backend.verification.domain.VerificationDocumentStatus;
import com.makaohub.backend.verification.domain.VerificationDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SublessorVerificationDocumentRepository
        extends JpaRepository<SublessorVerificationDocument, UUID> {

    Optional<SublessorVerificationDocument>
    findBySublessorIdAndDocumentTypeAndStatusIn(
            UUID sublessorId,
            VerificationDocumentType documentType,
            Collection<VerificationDocumentStatus> statuses
    );

    Optional<SublessorVerificationDocument> findByIdAndSublessorId(
            UUID id,
            UUID sublessorId
    );

    List<SublessorVerificationDocument> findAllBySublessorIdAndStatus(
            UUID sublessorId,
            VerificationDocumentStatus status
    );

    List<SublessorVerificationDocument> findAllBySublessorIdAndDocumentTypeInAndStatusIn(
            UUID sublessorId,
            Collection<VerificationDocumentType> types,
            Collection<VerificationDocumentStatus> statuses
    );
}
