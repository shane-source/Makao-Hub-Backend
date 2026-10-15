package com.makaohub.backend.property.api;

import com.makaohub.backend.property.domain.ListingMediaType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record CreateListingMediaUploadRequest(

        @NotNull(message = "Media type is required.")
        ListingMediaType mediaType,

        @NotBlank(message = "Content type is required.")
        @Size(max = 50, message = "Content type is too long.")
        String contentType,

        @Positive(message = "File size must be greater than zero.")
        @Max(
                value = 52428800,
                message = "File size cannot exceed 50 MB."
        )
        long sizeBytes,

        @Max(
                value = 30,
                message = "Sort order cannot exceed 30."
        )
        short sortOrder
) {
    public CreateListingMediaUploadRequest {
        if (contentType != null) {
            contentType = contentType
                    .strip()
                    .toLowerCase(Locale.ROOT);
        }
    }
}