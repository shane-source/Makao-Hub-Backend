package com.makaohub.backend.property.api;

import java.util.List;

public record RenterDashboardListingsResponse(
        List<PublicListingResponse> landlordListings,
        List<PublicSublessorListingResponse> sublessorListings
) {
    public RenterDashboardListingsResponse {
        landlordListings = landlordListings == null
                ? List.of()
                : List.copyOf(landlordListings);

        sublessorListings = sublessorListings == null
                ? List.of()
                : List.copyOf(sublessorListings);
    }
}