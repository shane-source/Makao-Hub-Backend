package com.makaohub.backend.property.api;

import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.PropertyType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ListingResponse(
        UUID id,
        String title,
        String description,
        PropertyType propertyType,
        long monthlyRentKes,
        short bedroomCount,
        short bathroomCount,
        boolean furnished,
        LocalDate availableFrom,
        ListingStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}