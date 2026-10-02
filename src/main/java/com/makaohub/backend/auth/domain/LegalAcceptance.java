package com.makaohub.backend.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "legal_acceptances")
public class LegalAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private LegalDocumentType documentType;

    @Column(name = "document_version", nullable = false, length = 50)
    private String documentVersion;

    @CreationTimestamp
    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    protected LegalAcceptance() {
    }

    public LegalAcceptance(
            User user,
            LegalDocumentType documentType,
            String documentVersion
    ) {
        this.user = Objects.requireNonNull(user);
        this.documentType =
                Objects.requireNonNull(documentType);

        if (documentVersion == null
                || documentVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "Document version cannot be blank."
            );
        }

        this.documentVersion = documentVersion;
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public LegalDocumentType getDocumentType() {
        return documentType;
    }

    public String getDocumentVersion() {
        return documentVersion;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }
}