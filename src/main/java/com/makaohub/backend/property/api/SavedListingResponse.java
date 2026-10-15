package com.makaohub.backend.property.api;

import java.time.Instant;
import java.util.UUID;

public record SavedListingResponse(
        UUID listingId,
        Instant savedAt
) {
}