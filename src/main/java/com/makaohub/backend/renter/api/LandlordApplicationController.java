package com.makaohub.backend.renter.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.renter.service.LandlordApplicationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/landlord/applications")
public class LandlordApplicationController {

    private final LandlordApplicationService applicationService;

    public LandlordApplicationController(
            LandlordApplicationService applicationService
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
            @PathVariable UUID applicationId
    ) {
        return applicationService.reject(
                extractUserId(jwt),
                applicationId
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