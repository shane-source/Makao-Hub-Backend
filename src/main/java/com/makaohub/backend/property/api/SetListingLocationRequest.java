package com.makaohub.backend.property.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record SetListingLocationRequest(

        @NotBlank(message = "Area label is required.")
        @Size(max = 150, message = "Area label must not exceed 150 characters.")
        String areaLabel,

        @NotNull(message = "Latitude is required.")
        @DecimalMin(value = "-5.0", message = "Latitude must be within Kenya.")
        @DecimalMax(value = "5.5", message = "Latitude must be within Kenya.")
        BigDecimal latitude,

        @NotNull(message = "Longitude is required.")
        @DecimalMin(value = "33.0", message = "Longitude must be within Kenya.")
        @DecimalMax(value = "42.5", message = "Longitude must be within Kenya.")
        BigDecimal longitude
) {
}