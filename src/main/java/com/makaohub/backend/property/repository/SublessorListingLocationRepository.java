package com.makaohub.backend.property.repository;

import com.makaohub.backend.property.domain.SublessorListingLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SublessorListingLocationRepository
        extends JpaRepository<SublessorListingLocation, UUID> {

    Optional<SublessorListingLocation>
    findBySublessorListingId(UUID sublessorListingId);
}