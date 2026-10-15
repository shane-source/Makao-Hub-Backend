package com.makaohub.backend.roommate.domain;

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
@Table(name = "roommate_interests")
public class RoommateInterest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "renter_id", nullable = false)
    private UUID renterId;

    @Column(name = "sublessor_listing_id", nullable = false)
    private UUID sublessorListingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoommateInterestStatus status =
            RoommateInterestStatus.SUBMITTED;

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

    protected RoommateInterest() {
    }

    public RoommateInterest(
            UUID renterId,
            UUID sublessorListingId
    ) {
        this.renterId = Objects.requireNonNull(renterId);
        this.sublessorListingId =
                Objects.requireNonNull(sublessorListingId);
    }

    public void markUnderReview() {
        requireStatus(RoommateInterestStatus.SUBMITTED);
        status = RoommateInterestStatus.UNDER_REVIEW;
    }

    public void accept() {
        requireStatus(RoommateInterestStatus.UNDER_REVIEW);
        status = RoommateInterestStatus.ACCEPTED;
    }

    public void decline() {
        requireStatus(RoommateInterestStatus.UNDER_REVIEW);
        status = RoommateInterestStatus.DECLINED;
    }

    public void withdraw() {
        if (status != RoommateInterestStatus.SUBMITTED
                && status != RoommateInterestStatus.UNDER_REVIEW) {
            throw new IllegalStateException(
                    "Only active roommate interests can be withdrawn."
            );
        }

        status = RoommateInterestStatus.WITHDRAWN;
    }

    private void requireStatus(
            RoommateInterestStatus expectedStatus
    ) {
        if (status != expectedStatus) {
            throw new IllegalStateException(
                    "Invalid roommate interest status transition."
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

    public RoommateInterestStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}