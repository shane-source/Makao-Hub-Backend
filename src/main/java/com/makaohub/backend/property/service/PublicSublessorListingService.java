package com.makaohub.backend.property.service;

import com.makaohub.backend.property.api.PublicListingMediaResponse;
import com.makaohub.backend.property.api.PublicSublessorListingResponse;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.SublessorListing;
import com.makaohub.backend.property.domain.SublessorListingLocation;
import com.makaohub.backend.property.domain.SublessorListingMedia;
import com.makaohub.backend.property.domain.ListingMediaType;
import com.makaohub.backend.property.repository.SublessorListingLocationRepository;
import com.makaohub.backend.property.repository.SublessorListingMediaRepository;
import com.makaohub.backend.property.repository.SublessorListingRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class PublicSublessorListingService {

    private final SublessorListingRepository listingRepository;
    private final SublessorListingLocationRepository locationRepository;
    private final SublessorListingMediaRepository mediaRepository;
    private final SupabaseStorageClient storageClient;

    public PublicSublessorListingService(
            SublessorListingRepository listingRepository,
            SublessorListingLocationRepository locationRepository,
            SublessorListingMediaRepository mediaRepository,
            SupabaseStorageClient storageClient
    ) {
        this.listingRepository = listingRepository;
        this.locationRepository = locationRepository;
        this.mediaRepository = mediaRepository;
        this.storageClient = storageClient;
    }

    @Transactional(readOnly = true)
    public List<PublicSublessorListingResponse>
    findPublishedListings() {
        return listingRepository
                .findAllByStatusOrderByCreatedAtDesc(
                        ListingStatus.PUBLISHED
                )
                .stream()
                .map(listing -> {
                    SublessorListingLocation location =
                            locationRepository
                                    .findBySublessorListingId(
                                            listing.getId()
                                    )
                                    .orElse(null);

                    return toResponse(listing, location);
                })
                .filter(response -> response != null)
                .toList();
    }

    @Transactional(readOnly = true)
    public PublicSublessorListingResponse findPublishedListing(
            UUID listingId
    ) {
        SublessorListing listing =
                listingRepository.findById(listingId)
                        .orElseThrow(
                                PublicSublessorListingService
                                        ::listingNotFound
                        );

        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw listingNotFound();
        }

        SublessorListingLocation location =
                locationRepository
                        .findBySublessorListingId(listingId)
                        .orElseThrow(
                                PublicSublessorListingService
                                        ::locationNotFound
                        );

        return toResponse(listing, location);
    }

    @Transactional(readOnly = true)
    public List<PublicListingMediaResponse> findPublishedMedia(
            UUID listingId
    ) {
        SublessorListing listing =
                listingRepository.findById(listingId)
                        .orElseThrow(
                                PublicSublessorListingService
                                        ::listingNotFound
                        );

        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw listingNotFound();
        }

        return mediaRepository
                .findAllBySublessorListingIdAndConfirmedAtIsNotNullOrderBySortOrderAsc(
                        listingId
                )
                .stream()
                .filter(media -> media.getMediaType() == ListingMediaType.IMAGE)
                .map(this::toMediaResponse)
                .toList();
    }

    private PublicSublessorListingResponse toResponse(
            SublessorListing listing,
            SublessorListingLocation location
    ) {
        if (location == null) {
            return null;
        }

        return new PublicSublessorListingResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getSpaceType(),
                listing.getMonthlyRentKes(),
                listing.getBathroomCount(),
                listing.isFurnished(),
                listing.getAvailableFrom(),
                listing.getStatus(),
                location.getAreaLabel(),
                location.getApproximateLatitude(),
                location.getApproximateLongitude(),
                location.getApproximateRadiusMeters(),
                listing.getUpdatedAt()
        );
    }

    private PublicListingMediaResponse toMediaResponse(
            SublessorListingMedia media
    ) {
        return new PublicListingMediaResponse(
                media.getId(),
                media.getMediaType(),
                media.getContentType(),
                media.getSortOrder(),
                storageClient.createSignedListingMediaReadUrl(
                        media.getObjectPath()
                )
        );
    }

    private static ResponseStatusException listingNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Published sublessor listing not found."
        );
    }

    private static ResponseStatusException locationNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Published listing location not found."
        );
    }
}
