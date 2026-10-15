package com.makaohub.backend.verification.domain;

import com.makaohub.backend.auth.domain.RoleName;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "role_verifications")
@IdClass(RoleVerificationId.class)
public class RoleVerification {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private RoleName role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VerificationStatus status =
            VerificationStatus.NOT_STARTED;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoleVerification() {
    }

    public RoleVerification(UUID userId, RoleName role) {
        this.userId = java.util.Objects.requireNonNull(userId);
        this.role = java.util.Objects.requireNonNull(role);
    }

    public RoleName getRole() {
        return role;
    }

    public UUID getUserId() {
        return userId;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void markPending() {
        this.status = VerificationStatus.PENDING;
        this.submittedAt = Instant.now();
        this.reviewedAt = null;
        this.reviewedBy = null;
        this.rejectionReason = null;
    }
}
