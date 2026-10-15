package com.makaohub.backend.property.api;

import com.makaohub.backend.property.domain.ListingMediaType;

import java.util.UUID;

public record PublicListingMediaResponse(

        UUID id,

        ListingMediaType mediaType,

        String contentType,

        short sortOrder,

        String signedReadUrl
) {
}