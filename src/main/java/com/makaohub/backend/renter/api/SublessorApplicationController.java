package com.makaohub.backend.renter.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.renter.service.SublessorApplicationReviewService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sublessor/applications")
public class SublessorApplicationController {
    public record Rejection(@NotBlank @Size(max=500) String reason) {}

    private final SublessorApplicationReviewService applicationService;

    public SublessorApplicationController(
            SublessorApplicationReviewService applicationService
    ) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public List<RentalApplicationResponse> getApplications(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return applicationService.findForMyListings(
                extractUserId(jwt)
        );
    }

    @PostMapping("/{applicationId}/review")
    public RentalApplicationResponse markUnderReview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        return applicationService.markUnderReview(
                extractUserId(jwt),
                applicationId
        );
    }

    @PostMapping("/{applicationId}/approve")
    public RentalApplicationResponse approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        return applicationService.approve(
                extractUserId(jwt),
                applicationId
        );
    }

    @PostMapping("/{applicationId}/reject")
    public RentalApplicationResponse reject(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId,
            @Valid @RequestBody Rejection request
    ) {
        return applicationService.reject(
                extractUserId(jwt),
                applicationId,
                request.reason()
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
