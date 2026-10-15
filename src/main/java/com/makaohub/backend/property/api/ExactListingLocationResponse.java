package com.makaohub.backend.property.api;

import java.math.BigDecimal;

/** Exact coordinates returned only after the matching listing unlock is paid. */
public record ExactListingLocationResponse(
        String areaLabel,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
