package com.makaohub.backend.property.repository;

import com.makaohub.backend.property.domain.ListingMedia;
import com.makaohub.backend.property.domain.ListingMediaType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ListingMediaRepository
        extends JpaRepository<ListingMedia, UUID> {

    List<ListingMedia> findAllByListingIdOrderBySortOrderAsc(
            UUID listingId
    );

    List<ListingMedia>
    findAllByListingIdAndConfirmedAtIsNotNullOrderBySortOrderAsc(
            UUID listingId
    );

    long countByListingIdAndMediaType(
            UUID listingId,
            ListingMediaType mediaType
    );

    long countByListingIdAndMediaTypeAndConfirmedAtIsNotNull(
            UUID listingId,
            ListingMediaType mediaType
    );

    boolean existsByListingIdAndSortOrder(
            UUID listingId,
            short sortOrder
    );
}