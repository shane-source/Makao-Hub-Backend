package com.makaohub.backend.verification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "renter_verification_documents")
public class RenterVerificationDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "renter_id", nullable = false)
    private UUID renterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private VerificationDocumentType documentType;

    @Column(name = "object_path", nullable = false, length = 255)
    private String objectPath;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationDocumentStatus status =
            VerificationDocumentStatus.UPLOADING;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RenterVerificationDocument() {
    }

    public RenterVerificationDocument(
            UUID renterId,
            VerificationDocumentType documentType,
            String objectPath,
            String contentType
    ) {
        this.renterId = Objects.requireNonNull(renterId);
        this.documentType = Objects.requireNonNull(documentType);
        this.objectPath = Objects.requireNonNull(objectPath);
        this.contentType = Objects.requireNonNull(contentType);
    }

    public UUID getId() {
        return id;
    }

    public UUID getRenterId() {
        return renterId;
    }

    public VerificationDocumentType getDocumentType() {
        return documentType;
    }

    public String getObjectPath() {
        return objectPath;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public VerificationDocumentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markReady(long sizeBytes) {
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "Document size must be greater than zero."
            );
        }

        this.status = VerificationDocumentStatus.READY;
        this.sizeBytes = sizeBytes;
    }

    public boolean usesImmutableEvidencePath() {
        return objectPath.startsWith(renterId + "/renter-evidence-v1/");
    }
    public boolean hasCurrentSecurityChecks() {
        return status == VerificationDocumentStatus.READY && usesImmutableEvidencePath();
    }
    public void supersede() { status = VerificationDocumentStatus.DELETED; }
}
