package com.makaohub.backend.property.repository;

import com.makaohub.backend.property.domain.SavedListing;
import com.makaohub.backend.property.domain.SavedListingId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SavedListingRepository
        extends JpaRepository<SavedListing, SavedListingId> {

    List<SavedListing> findAllByUserIdOrderByCreatedAtDesc(
            UUID userId
    );

    boolean existsByUserIdAndListingId(
            UUID userId,
            UUID listingId
    );

    long deleteByUserIdAndListingId(
            UUID userId,
            UUID listingId
    );
}