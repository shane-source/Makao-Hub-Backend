package com.makaohub.backend.property.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.property.service.SublessorListingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sublessor/listings")
public class SublessorListingController {

    private final SublessorListingService listingService;

    public SublessorListingController(
            SublessorListingService listingService
    ) {
        this.listingService = listingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SublessorListingResponse createDraft(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateSublessorListingRequest request
    ) {
        return listingService.createDraft(
                extractUserId(jwt),
                request
        );
    }

    @GetMapping
    public List<SublessorListingResponse> getMyListings(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return listingService.getMyListings(
                extractUserId(jwt)
        );
    }

    @PutMapping("/{listingId}")
    public SublessorListingResponse updateDraft(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId,
            @Valid @RequestBody CreateSublessorListingRequest request
    ) {
        return listingService.updateDraft(
                extractUserId(jwt),
                listingId,
                request
        );
    }

    @PostMapping("/{listingId}/publish")
    public SublessorListingResponse publish(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        return listingService.publish(
                extractUserId(jwt),
                listingId
        );
    }

    @PostMapping("/{listingId}/pause")
    public SublessorListingResponse pause(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        return listingService.pause(
                extractUserId(jwt),
                listingId
        );
    }

    @PostMapping("/{listingId}/resume")
    public SublessorListingResponse resume(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        return listingService.resume(
                extractUserId(jwt),
                listingId
        );
    }

    @PostMapping("/{listingId}/archive")
    public SublessorListingResponse archive(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        return listingService.archive(
                extractUserId(jwt),
                listingId
        );
    }

    private UUID extractUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw accountUnavailable();
        }

        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw accountUnavailable();
        }
    }

    private AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}