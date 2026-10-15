package com.makaohub.backend.roommate.api;

import com.makaohub.backend.roommate.domain.RoommateInterestStatus;

import java.time.Instant;
import java.util.UUID;

public record RoommateInterestResponse(
        UUID id,
        UUID sublessorListingId,
        RoommateInterestStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}