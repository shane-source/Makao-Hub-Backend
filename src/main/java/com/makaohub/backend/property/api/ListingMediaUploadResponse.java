package com.makaohub.backend.property.api;

import com.makaohub.backend.property.domain.ListingMediaType;

import java.util.UUID;

public record ListingMediaUploadResponse(

        UUID mediaId,

        ListingMediaType mediaType,

        String contentType,

        long sizeBytes,

        short sortOrder,

        String uploadUrl
) {
}