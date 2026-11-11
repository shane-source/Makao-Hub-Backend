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

    @Query(value = """
            SELECT l.* FROM listings l JOIN users u ON u.id=l.landlord_id
            WHERE l.status = 'PUBLISHED' AND u.account_status='ACTIVE'
            ORDER BY CASE WHEN EXISTS (
                SELECT 1 FROM landlord_offer_grants g WHERE g.listing_id = l.id
                AND g.starts_at <= CURRENT_TIMESTAMP AND g.expires_at > CURRENT_TIMESTAMP
            ) THEN 0 ELSE 1 END, l.updated_at DESC, l.id DESC
            """, countQuery = "SELECT COUNT(*) FROM listings l JOIN users u ON u.id=l.landlord_id WHERE l.status = 'PUBLISHED' AND u.account_status='ACTIVE'", nativeQuery = true)
    Page<Listing> findPublishedSponsoredFirst(Pageable pageable);

    @Query(value = """
            SELECT l.* FROM listings l JOIN users u ON u.id=l.landlord_id
            WHERE l.status = 'PUBLISHED' AND u.account_status='ACTIVE'
              AND (CAST(:minRentKes AS bigint) IS NULL OR l.monthly_rent_kes >= :minRentKes)
              AND (CAST(:maxRentKes AS bigint) IS NULL OR l.monthly_rent_kes <= :maxRentKes)
              AND (CAST(:propertyType AS text) IS NULL OR l.property_type = :propertyType)
              AND (CAST(:minBedrooms AS integer) IS NULL OR l.bedroom_count >= :minBedrooms)
              AND (CAST(:furnished AS boolean) IS NULL OR l.furnished = :furnished)
              AND (CAST(:areaLabel AS text) IS NULL OR EXISTS (
                SELECT 1 FROM listing_locations x WHERE x.listing_id = l.id
                  AND LOWER(x.area_label) LIKE LOWER(CONCAT('%', :areaLabel, '%'))))
            ORDER BY CASE WHEN EXISTS (
                SELECT 1 FROM landlord_offer_grants g WHERE g.listing_id = l.id
                AND g.starts_at <= CURRENT_TIMESTAMP AND g.expires_at > CURRENT_TIMESTAMP
            ) THEN 0 ELSE 1 END, l.updated_at DESC, l.id DESC
            """, countQuery = """
            SELECT COUNT(*) FROM listings l JOIN users u ON u.id=l.landlord_id
            WHERE l.status = 'PUBLISHED' AND u.account_status='ACTIVE'
              AND (CAST(:minRentKes AS bigint) IS NULL OR l.monthly_rent_kes >= :minRentKes)
              AND (CAST(:maxRentKes AS bigint) IS NULL OR l.monthly_rent_kes <= :maxRentKes)
              AND (CAST(:propertyType AS text) IS NULL OR l.property_type = :propertyType)
              AND (CAST(:minBedrooms AS integer) IS NULL OR l.bedroom_count >= :minBedrooms)
              AND (CAST(:furnished AS boolean) IS NULL OR l.furnished = :furnished)
              AND (CAST(:areaLabel AS text) IS NULL OR EXISTS (
                SELECT 1 FROM listing_locations x WHERE x.listing_id = l.id
                  AND LOWER(x.area_label) LIKE LOWER(CONCAT('%', :areaLabel, '%'))))
            """, nativeQuery = true)
    Page<Listing> searchListings(
            @Param("minRentKes") Long minRentKes,
            @Param("maxRentKes") Long maxRentKes,
            @Param("propertyType") String propertyType,
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

    @Query("""
            SELECT l FROM Listing l WHERE l.id=:id AND l.status=:status
            AND EXISTS (SELECT 1 FROM User u WHERE u.id=l.landlordId AND u.accountStatus=com.makaohub.backend.auth.domain.AccountStatus.ACTIVE)
            """)
    Optional<Listing> findByIdAndStatus(@Param("id") UUID id, @Param("status") ListingStatus status);
}
