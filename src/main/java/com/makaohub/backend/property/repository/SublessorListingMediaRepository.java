package com.makaohub.backend.property.repository;

import com.makaohub.backend.property.domain.ListingMediaType;
import com.makaohub.backend.property.domain.SublessorListingMedia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SublessorListingMediaRepository
        extends JpaRepository<SublessorListingMedia, UUID> {

    boolean existsBySublessorListingIdAndMediaTypeAndConfirmedAtIsNotNull(UUID listingId, ListingMediaType mediaType);

    boolean existsBySublessorListingIdAndSortOrder(
            UUID sublessorListingId,
            short sortOrder
    );

    long countBySublessorListingIdAndMediaType(
            UUID sublessorListingId,
            ListingMediaType mediaType
    );

    Optional<SublessorListingMedia>
    findByIdAndSublessorListingId(
            UUID mediaId,
            UUID sublessorListingId
    );

    List<SublessorListingMedia>
    findAllBySublessorListingIdOrderBySortOrderAsc(
            UUID sublessorListingId
    );

    List<SublessorListingMedia>
    findAllBySublessorListingIdAndConfirmedAtIsNotNullOrderBySortOrderAsc(
            UUID sublessorListingId
    );

    boolean existsBySublessorListingIdAndConfirmedAtIsNotNull(
            UUID sublessorListingId
    );
}
