package com.makaohub.backend.property.api;

import java.math.BigDecimal;

public record LandlordListingLocationResponse(
        String areaLabel,
        BigDecimal latitude,
        BigDecimal longitude
) {
}