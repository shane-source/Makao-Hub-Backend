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
@Table(name = "landlord_verification_documents")
public class LandlordVerificationDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "landlord_id", nullable = false)
    private UUID landlordId;

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

    protected LandlordVerificationDocument() {
    }

    public LandlordVerificationDocument(
            UUID landlordId,
            VerificationDocumentType documentType,
            String objectPath,
            String contentType
    ) {
        this.landlordId = Objects.requireNonNull(landlordId);
        this.documentType = Objects.requireNonNull(documentType);
        this.objectPath = Objects.requireNonNull(objectPath);
        this.contentType = Objects.requireNonNull(contentType);
    }

    public UUID getId() {
        return id;
    }

    public UUID getLandlordId() {
        return landlordId;
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

    public void markReady(long sizeBytes) {
        this.status = VerificationDocumentStatus.READY;
        this.sizeBytes = sizeBytes;
    }
}