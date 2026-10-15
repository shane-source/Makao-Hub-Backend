package com.makaohub.backend.property.repository;

import com.makaohub.backend.property.domain.ListingLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ListingLocationRepository
        extends JpaRepository<ListingLocation, UUID> {

    List<ListingLocation> findAllByListingIdIn(
            Collection<UUID> listingIds
    );
}