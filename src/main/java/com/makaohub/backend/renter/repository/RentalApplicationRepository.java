package com.makaohub.backend.renter.repository;

import com.makaohub.backend.renter.domain.RentalApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RentalApplicationRepository
        extends JpaRepository<RentalApplication, UUID> {

    List<RentalApplication> findAllByRenterIdOrderByCreatedAtDesc(
            UUID renterId
    );
    List<RentalApplication> findAllByListingIdInOrderByCreatedAtDesc(
            Collection<UUID> listingIds
    );

    Optional<RentalApplication> findByIdAndRenterId(
            UUID applicationId,
            UUID renterId
    );

    boolean existsByRenterIdAndListingId(
            UUID renterId,
            UUID listingId
    );
}