package com.makaohub.backend.property.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.property.service.SavedListingService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/renter/saved-listings")
public class SavedListingController {

    private final SavedListingService savedListingService;

    public SavedListingController(
            SavedListingService savedListingService
    ) {
        this.savedListingService = savedListingService;
    }

    @GetMapping
    public List<SavedListingResponse> getSavedListings(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return savedListingService.findSaved(
                extractUserId(jwt)
        );
    }

    @GetMapping("/compare")
    public List<PublicListingResponse> compareSavedListings(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam List<UUID> listingIds
    ) {
        return savedListingService.compare(
                extractUserId(jwt),
                listingIds
        );
    }

    @PostMapping("/{listingId}")
    @ResponseStatus(HttpStatus.CREATED)
    public SavedListingResponse saveListing(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        return savedListingService.save(
                extractUserId(jwt),
                listingId
        );
    }

    @DeleteMapping("/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSavedListing(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        savedListingService.remove(
                extractUserId(jwt),
                listingId
        );
    }

    private UUID extractUserId(Jwt jwt) {
        String subject = jwt.getSubject();

        if (subject == null) {
            throw accountUnavailable();
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw accountUnavailable();
        }
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}