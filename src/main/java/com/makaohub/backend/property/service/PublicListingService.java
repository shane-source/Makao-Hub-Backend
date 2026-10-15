package com.makaohub.backend.property.service;

import com.makaohub.backend.property.api.PublicListingMediaResponse;
import com.makaohub.backend.property.api.PublicListingResponse;
import com.makaohub.backend.property.domain.Listing;
import com.makaohub.backend.property.domain.ListingLocation;
import com.makaohub.backend.property.domain.ListingMedia;
import com.makaohub.backend.property.domain.ListingMediaType;
import com.makaohub.backend.property.domain.ListingStatus;
import com.makaohub.backend.property.domain.PropertyType;
import com.makaohub.backend.property.repository.ListingLocationRepository;
import com.makaohub.backend.property.repository.ListingMediaRepository;
import com.makaohub.backend.property.repository.ListingRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PublicListingService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_AREA_LENGTH = 150;

    private final ListingRepository listingRepository;
    private final ListingLocationRepository locationRepository;
    private final ListingMediaRepository mediaRepository;
    private final SupabaseStorageClient storageClient;

    public PublicListingService(
            ListingRepository listingRepository,
            ListingLocationRepository locationRepository,
            ListingMediaRepository mediaRepository,
            SupabaseStorageClient storageClient
    ) {
        this.listingRepository = listingRepository;
        this.locationRepository = locationRepository;
        this.mediaRepository = mediaRepository;
        this.storageClient = storageClient;
    }

    @Transactional(readOnly = true)
    public List<PublicListingResponse> findPublishedListings() {
        return findPublishedListings(
                0,
                MAX_PAGE_SIZE,
                null,
                null,
                null,
                null,
                null,
                null
        ).getContent();
    }

    @Transactional(readOnly = true)
    public Page<PublicListingResponse> findPublishedListings(
            int page,
            int size
    ) {
        return findPublishedListings(
                page,
                size,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    @Transactional(readOnly = true)
    public Page<PublicListingResponse> findPublishedListings(
            int page,
            int size,
            Long minRentKes,
            Long maxRentKes
    ) {
        return findPublishedListings(
                page,
                size,
                minRentKes,
                maxRentKes,
                null,
                null,
                null,
                null
        );
    }

    @Transactional(readOnly = true)
    public Page<PublicListingResponse> findPublishedListings(
            int page,
            int size,
            Long minRentKes,
            Long maxRentKes,
            PropertyType propertyType,
            Integer minBedrooms,
            String areaLabel
    ) {
        return findPublishedListings(
                page,
                size,
                minRentKes,
                maxRentKes,
                propertyType,
                minBedrooms,
                areaLabel,
                null
        );
    }

    @Transactional(readOnly = true)
    public Page<PublicListingResponse> findPublishedListings(
            int page,
            int size,
            Long minRentKes,
            Long maxRentKes,
            PropertyType propertyType,
            Integer minBedrooms,
            String areaLabel,
            Boolean furnished
    ) {
        validateRentRange(minRentKes, maxRentKes);

        if (minBedrooms != null && minBedrooms < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Minimum bedrooms cannot be negative."
            );
        }

        String normalizedAreaLabel = normalizeAreaLabel(areaLabel);

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(
                Math.max(size, 1),
                MAX_PAGE_SIZE
        );

        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by(
                        Sort.Direction.DESC,
                        "updatedAt"
                )
        );

        boolean noFilters = minRentKes == null
                && maxRentKes == null
                && propertyType == null
                && minBedrooms == null
                && furnished == null
                && normalizedAreaLabel == null;

        Page<Listing> listingPage = noFilters
                ? listingRepository.findAllByStatus(
                ListingStatus.PUBLISHED,
                pageable
        )
                : listingRepository.searchListings(
                ListingStatus.PUBLISHED,
                minRentKes,
                maxRentKes,
                propertyType,
                minBedrooms,
                furnished,
                normalizedAreaLabel,
                pageable
        );

        List<Listing> listings = listingPage.getContent();

        if (listings.isEmpty()) {
            return new PageImpl<>(
                    List.of(),
                    listingPage.getPageable(),
                    listingPage.getTotalElements()
            );
        }

        List<UUID> listingIds = listings.stream()
                .map(Listing::getId)
                .toList();

        Map<UUID, ListingLocation> locations =
                locationRepository.findAllByListingIdIn(listingIds)
                        .stream()
                        .collect(Collectors.toMap(
                                ListingLocation::getListingId,
                                Function.identity()
                        ));

        List<PublicListingResponse> responses = listings.stream()
                .map(listing -> toResponse(
                        listing,
                        locations.get(listing.getId())
                ))
                .filter(response -> response != null)
                .toList();

        return new PageImpl<>(
                responses,
                listingPage.getPageable(),
                listingPage.getTotalElements()
        );
    }

    @Transactional(readOnly = true)
    public PublicListingResponse findPublishedListing(
            UUID listingId
    ) {
        Listing listing = listingRepository
                .findByIdAndStatus(
                        listingId,
                        ListingStatus.PUBLISHED
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Published listing not found."
                ));

        ListingLocation location = locationRepository
                .findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Published listing location not found."
                ));

        return toResponse(listing, location);
    }

    @Transactional(readOnly = true)
    public List<PublicListingMediaResponse> findPublishedMedia(
            UUID listingId
    ) {
        listingRepository
                .findByIdAndStatus(
                        listingId,
                        ListingStatus.PUBLISHED
                )
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Published listing not found."
                ));

        return mediaRepository
                .findAllByListingIdAndConfirmedAtIsNotNullOrderBySortOrderAsc(
                        listingId
                )
                .stream()
                .filter(media -> media.getMediaType() == ListingMediaType.IMAGE)
                .map(this::toMediaResponse)
                .filter(response -> response.signedReadUrl() != null)
                .toList();
    }

    private void validateRentRange(
            Long minRentKes,
            Long maxRentKes
    ) {
        if (minRentKes != null && minRentKes < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Minimum rent cannot be negative."
            );
        }

        if (maxRentKes != null && maxRentKes < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Maximum rent cannot be negative."
            );
        }

        if (minRentKes != null
                && maxRentKes != null
                && minRentKes > maxRentKes) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Minimum rent cannot exceed maximum rent."
            );
        }
    }

    private String normalizeAreaLabel(String areaLabel) {
        if (areaLabel == null) {
            return null;
        }

        String normalized = areaLabel.strip();

        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.length() > MAX_AREA_LENGTH) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Area label is too long."
            );
        }

        return normalized;
    }

    private PublicListingResponse toResponse(
            Listing listing,
            ListingLocation location
    ) {
        if (location == null) {
            return null;
        }

        return new PublicListingResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getPropertyType(),
                listing.getMonthlyRentKes(),
                listing.getBedroomCount(),
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
            ListingMedia media
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
}
