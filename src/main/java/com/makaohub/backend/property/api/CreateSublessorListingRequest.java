package com.makaohub.backend.property.api;

import com.makaohub.backend.property.domain.SublessorSpaceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateSublessorListingRequest(

        @NotBlank(message = "Listing title is required.")
        @Size(
                max = 150,
                message = "Listing title must not exceed 150 characters."
        )
        String title,

        @NotBlank(message = "Listing description is required.")
        @Size(
                max = 3000,
                message = "Listing description must not exceed 3000 characters."
        )
        String description,

        @NotNull(message = "Space type is required.")
        SublessorSpaceType spaceType,

        @Positive(message = "Monthly rent must be greater than zero.")
        long monthlyRentKes,

        @Min(
                value = 1,
                message = "Bathroom count must be at least one."
        )
        @Max(
                value = 20,
                message = "Bathroom count cannot exceed 20."
        )
        short bathroomCount,

        boolean furnished,

        LocalDate availableFrom
) {
}