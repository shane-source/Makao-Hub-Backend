package com.makaohub.backend.property.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.property.service.SublessorListingMediaService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sublessor/listings")
public class SublessorListingMediaController {

    private final SublessorListingMediaService mediaService;

    public SublessorListingMediaController(
            SublessorListingMediaService mediaService
    ) {
        this.mediaService = mediaService;
    }

    @PostMapping("/{listingId}/media/upload-url")
    @ResponseStatus(HttpStatus.CREATED)
    public ListingMediaUploadResponse createUploadUrl(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId,
            @Valid @RequestBody
            CreateListingMediaUploadRequest request
    ) {
        return mediaService.createUploadUrl(
                extractUserId(jwt),
                listingId,
                request
        );
    }

    @PostMapping("/{listingId}/media/{mediaId}/confirm")
    public ListingMediaResponse confirmUpload(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId,
            @PathVariable UUID mediaId
    ) {
        return mediaService.confirmUpload(
                extractUserId(jwt),
                listingId,
                mediaId
        );
    }

    @GetMapping("/{listingId}/media")
    public List<ListingMediaResponse> getListingMedia(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        return mediaService.findForListing(
                extractUserId(jwt),
                listingId
        );
    }

    @DeleteMapping("/{listingId}/media/{mediaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMedia(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId,
            @PathVariable UUID mediaId
    ) {
        mediaService.deleteMedia(
                extractUserId(jwt),
                listingId,
                mediaId
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