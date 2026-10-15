package com.makaohub.backend.renter.repository;

import com.makaohub.backend.renter.domain.SublessorRentalApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SublessorRentalApplicationRepository
        extends JpaRepository<
        SublessorRentalApplication,
        UUID
        > {

    boolean existsByRenterIdAndSublessorListingId(
            UUID renterId,
            UUID sublessorListingId
    );

    Optional<SublessorRentalApplication>
    findByIdAndRenterId(
            UUID applicationId,
            UUID renterId
    );

    List<SublessorRentalApplication>
    findAllByRenterIdOrderByCreatedAtDesc(
            UUID renterId
    );

    List<SublessorRentalApplication>
    findAllBySublessorListingIdInOrderByCreatedAtDesc(
            List<UUID> sublessorListingIds
    );
}