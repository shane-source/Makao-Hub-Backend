package com.makaohub.backend.roommate.repository;

import com.makaohub.backend.roommate.domain.RoommateInterest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoommateInterestRepository
        extends JpaRepository<RoommateInterest, UUID> {

    boolean existsByRenterIdAndSublessorListingId(
            UUID renterId,
            UUID sublessorListingId
    );

    Optional<RoommateInterest> findByIdAndRenterId(
            UUID interestId,
            UUID renterId
    );

    List<RoommateInterest> findAllByRenterIdOrderByCreatedAtDesc(
            UUID renterId
    );

    List<RoommateInterest>
    findAllBySublessorListingIdInOrderByCreatedAtDesc(
            List<UUID> sublessorListingIds
    );
}