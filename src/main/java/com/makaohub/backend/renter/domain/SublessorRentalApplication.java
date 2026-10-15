package com.makaohub.backend.renter.domain;

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
@Table(name = "sublessor_rental_applications")
public class SublessorRentalApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "renter_id", nullable = false)
    private UUID renterId;

    @Column(name = "sublessor_listing_id", nullable = false)
    private UUID sublessorListingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RentalApplicationStatus status =
            RentalApplicationStatus.SUBMITTED;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SublessorRentalApplication() {
    }

    public SublessorRentalApplication(
            UUID renterId,
            UUID sublessorListingId
    ) {
        this.renterId = Objects.requireNonNull(renterId);
        this.sublessorListingId =
                Objects.requireNonNull(sublessorListingId);
    }

    public void markUnderReview() {
        requireStatus(RentalApplicationStatus.SUBMITTED);
        status = RentalApplicationStatus.UNDER_REVIEW;
    }

    public void approve() {
        requireStatus(RentalApplicationStatus.UNDER_REVIEW);
        status = RentalApplicationStatus.APPROVED;
    }

    public void reject() {
        requireStatus(RentalApplicationStatus.UNDER_REVIEW);
        status = RentalApplicationStatus.REJECTED;
    }

    public void reject(String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > 500) throw new IllegalArgumentException("A rejection reason is required.");
        reject();
        rejectionReason = reason.strip();
    }

    public String getRejectionReason() { return rejectionReason; }

    public void withdraw() {
        if (status != RentalApplicationStatus.SUBMITTED
                && status != RentalApplicationStatus.UNDER_REVIEW) {
            throw new IllegalStateException(
                    "Only active applications can be withdrawn."
            );
        }

        status = RentalApplicationStatus.WITHDRAWN;
    }

    private void requireStatus(
            RentalApplicationStatus expectedStatus
    ) {
        if (status != expectedStatus) {
            throw new IllegalStateException(
                    "Invalid application status transition."
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getRenterId() {
        return renterId;
    }

    public UUID getSublessorListingId() {
        return sublessorListingId;
    }

    public RentalApplicationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
