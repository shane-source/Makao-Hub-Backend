package com.makaohub.backend.property.repository;

import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SublessorListing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SublessorListingRepository
        extends JpaRepository<SublessorListing, UUID> {

    Optional<SublessorListing> findByIdAndSublessorId(
            UUID listingId,
            UUID sublessorId
    );

    List<SublessorListing> findAllBySublessorIdOrderByCreatedAtDesc(
            UUID sublessorId
    );

    long countBySublessorId(UUID sublessorId);

    long countBySublessorIdAndStatus(
            UUID sublessorId,
            ListingStatus status
    );

    List<SublessorListing> findAllByStatusOrderByCreatedAtDesc(
            ListingStatus status
    );
}