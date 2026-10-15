package com.makaohub.backend.property.api;

public record LandlordDashboardResponse(
        long totalListings,
        long draftListings,
        long publishedListings
) {
}