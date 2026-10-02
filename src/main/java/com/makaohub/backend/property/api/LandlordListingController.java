package com.makaohub.backend.property.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.property.service.LandlordListingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/landlord/listings")
public class LandlordListingController {

    private final LandlordListingService listingService;

    public LandlordListingController(
            LandlordListingService listingService
    ) {
        this.listingService = listingService;
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingResponse createDraft(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateListingDraftRequest request
    ) {
        return listingService.createDraft(extractUserId(jwt), request);
    }

    @GetMapping
    public List<ListingResponse> getMyListings(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return listingService.getMyListings(extractUserId(jwt));
    }

    @PatchMapping("/{listingId}")
    public ListingResponse updateDraft(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId,
            @Valid @RequestBody CreateListingDraftRequest request
    ) {
        return listingService.updateDraft(
                extractUserId(jwt),
                listingId,
                request
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