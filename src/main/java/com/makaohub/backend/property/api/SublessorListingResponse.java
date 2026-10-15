package com.makaohub.backend.property.api;

import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SublessorSpaceType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SublessorListingResponse(
        UUID id,
        String title,
        String description,
        SublessorSpaceType spaceType,
        long monthlyRentKes,
        short bathroomCount,
        boolean furnished,
        LocalDate availableFrom,
        ListingStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}