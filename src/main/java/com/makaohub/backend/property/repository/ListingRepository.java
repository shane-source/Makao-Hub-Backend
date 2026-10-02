package com.makaohub.backend.property.repository;

import com.makaohub.backend.property.domain.Listing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingRepository
        extends JpaRepository<Listing, UUID> {

    List<Listing> findAllByLandlordIdOrderByUpdatedAtDesc(
            UUID landlordId
    );

    Optional<Listing> findByIdAndLandlordId(
            UUID id,
            UUID landlordId
    );
}