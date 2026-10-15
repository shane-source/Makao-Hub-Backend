package com.makaohub.backend.property.api;

import com.makaohub.backend.property.service.RenterListingLocationAccessService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

@RestController
@Profile("mock")
@RequestMapping("/api/v1/renter/listings")
public class RenterListingLocationController {

    private final RenterListingLocationAccessService locationService;
    private final com.makaohub.backend.property.service.RenterHomeService homes;

    public RenterListingLocationController(
            RenterListingLocationAccessService locationService,
            com.makaohub.backend.property.service.RenterHomeService homes
    ) {
        this.locationService = locationService;
        this.homes = homes;
    }

    @GetMapping("/{listingId}/location/exact")
    public ExactListingLocationResponse getExactLocation(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        if (jwt == null || jwt.getSubject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid access token is required.");
        }
        try {
            homes.access(UUID.fromString(jwt.getSubject()), listingId);
            return locationService.getExactLocation(UUID.fromString(jwt.getSubject()), listingId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid access token is required.");
        }
    }
}
