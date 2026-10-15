package com.makaohub.backend.property.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "saved_listings")
@IdClass(SavedListingId.class)
public class SavedListing {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SavedListing() {
    }

    public SavedListing(
            UUID userId,
            UUID listingId
    ) {
        this.userId = Objects.requireNonNull(userId);
        this.listingId = Objects.requireNonNull(listingId);
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getListingId() {
        return listingId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}