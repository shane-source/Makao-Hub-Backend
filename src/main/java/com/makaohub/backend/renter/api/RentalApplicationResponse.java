package com.makaohub.backend.renter.api;

import com.makaohub.backend.renter.domain.RentalApplicationStatus;

import java.time.Instant;
import java.util.UUID;

public record RentalApplicationResponse(

        UUID id,

        UUID listingId,

        RentalApplicationStatus status,

        Instant createdAt,

        Instant updatedAt,
        String rejectionReason
) {
    public RentalApplicationResponse(UUID id, UUID listingId, RentalApplicationStatus status, Instant createdAt, Instant updatedAt) {
        this(id, listingId, status, createdAt, updatedAt, null);
    }
}
