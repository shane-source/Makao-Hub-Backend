package com.makaohub.backend.property.repository;

import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.PropertyType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    List<Listing> findAllByStatusOrderByUpdatedAtDesc(
            ListingStatus status
    );

    Page<Listing> findAllByStatus(
            ListingStatus status,
            Pageable pageable
    );

    @Query("""
            SELECT l
            FROM Listing l
            WHERE l.status = :status
              AND (:minRentKes IS NULL
                   OR l.monthlyRentKes >= :minRentKes)
              AND (:maxRentKes IS NULL
                   OR l.monthlyRentKes <= :maxRentKes)
              AND (:propertyType IS NULL
                   OR l.propertyType = :propertyType)
              AND (:minBedrooms IS NULL
                   OR l.bedroomCount >= :minBedrooms)
              AND (:furnished IS NULL
                   OR l.furnished = :furnished)
              AND (
                    :areaLabel IS NULL
                    OR EXISTS (
                        SELECT location
                        FROM ListingLocation location
                        WHERE location.listingId = l.id
                          AND LOWER(location.areaLabel)
                              LIKE LOWER(CONCAT('%', :areaLabel, '%'))
                    )
              )
            ORDER BY l.updatedAt DESC
            """)
    Page<Listing> searchListings(
            @Param("status") ListingStatus status,
            @Param("minRentKes") Long minRentKes,
            @Param("maxRentKes") Long maxRentKes,
            @Param("propertyType") PropertyType propertyType,
            @Param("minBedrooms") Integer minBedrooms,
            @Param("furnished") Boolean furnished,
            @Param("areaLabel") String areaLabel,
            Pageable pageable
    );

    long countByLandlordId(UUID landlordId);

    long countByLandlordIdAndStatus(
            UUID landlordId,
            ListingStatus status
    );

    Optional<Listing> findByIdAndStatus(
            UUID id,
            ListingStatus status
    );
}