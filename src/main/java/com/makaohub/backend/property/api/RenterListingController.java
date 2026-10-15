package com.makaohub.backend.property.api;

import com.makaohub.backend.property.domain.PropertyType;
import com.makaohub.backend.property.service.PublicListingService;
import com.makaohub.backend.property.service.RenterListingVideoAccessService;
import com.makaohub.backend.property.service.RenterListingPhoneAccessService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/renter/listings")
public class RenterListingController {

    private final PublicListingService listingService;
    private final RenterListingVideoAccessService videoAccessService;
    private final RenterListingPhoneAccessService phoneAccessService;
    private final com.makaohub.backend.property.service.RenterHomeService homes;

    public RenterListingController(
            PublicListingService listingService,
            RenterListingVideoAccessService videoAccessService,
            RenterListingPhoneAccessService phoneAccessService,
            com.makaohub.backend.property.service.RenterHomeService homes
    ) {
        this.listingService = listingService;
        this.videoAccessService = videoAccessService;
        this.phoneAccessService = phoneAccessService;
        this.homes = homes;
    }

    @GetMapping
    public List<PublicListingResponse> getPublishedListings() {
        return listingService.findPublishedListings();
    }

    @GetMapping("/search")
    public Page<PublicListingResponse> searchPublishedListings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long minRentKes,
            @RequestParam(required = false) Long maxRentKes,
            @RequestParam(required = false) PropertyType propertyType,
            @RequestParam(required = false) Integer minBedrooms,
            @RequestParam(required = false) String areaLabel,
            @RequestParam(required = false) Boolean furnished
    ) {
        return listingService.findPublishedListings(
                page,
                size,
                minRentKes,
                maxRentKes,
                propertyType,
                minBedrooms,
                areaLabel,
                furnished
        );
    }

    @GetMapping("/{listingId}")
    public PublicListingResponse getPublishedListing(
            @PathVariable UUID listingId
    ) {
        return listingService.findPublishedListing(listingId);
    }

    @GetMapping("/{listingId}/media")
    public List<PublicListingMediaResponse> getListingMedia(
            @PathVariable UUID listingId
    ) {
        return listingService.findPublishedMedia(listingId);
    }

    @GetMapping("/{listingId}/media/video")
    public List<PublicListingMediaResponse> getListingVideos(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        homes.access(extractUserId(jwt), listingId);
        return videoAccessService.getVideos(extractUserId(jwt), listingId);
    }

    @GetMapping("/{listingId}/phone")
    public ListingOwnerPhoneResponse getListingOwnerPhone(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        homes.access(extractUserId(jwt), listingId);
        return phoneAccessService.getOwnerPhone(extractUserId(jwt), listingId);
    }

    private UUID extractUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "A valid access token is required."
            );
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "A valid access token is required."
            );
        }
    }
}
