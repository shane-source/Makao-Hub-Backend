package com.makaohub.backend.property.api;

import com.makaohub.backend.property.domain.ListingMediaType;

import java.time.Instant;
import java.util.UUID;

public record ListingMediaResponse(

        UUID id,

        UUID listingId,

        ListingMediaType mediaType,

        String contentType,

        long sizeBytes,

        short sortOrder,

        Instant createdAt,
        boolean confirmed
) {
    public ListingMediaResponse(UUID id, UUID listingId, ListingMediaType mediaType, String contentType, long sizeBytes, short sortOrder, Instant createdAt) {
        this(id,listingId,mediaType,contentType,sizeBytes,sortOrder,createdAt,false);
    }
}
