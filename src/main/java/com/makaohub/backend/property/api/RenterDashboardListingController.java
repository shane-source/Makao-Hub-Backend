package com.makaohub.backend.property.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.property.service.RenterDashboardListingService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/renter/dashboard")
public class RenterDashboardListingController {

    private final RenterDashboardListingService dashboardService;

    public RenterDashboardListingController(
            RenterDashboardListingService dashboardService
    ) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/listings")
    public RenterDashboardListingsResponse getDashboardListings(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return dashboardService.getDashboardListings(
                extractUserId(jwt)
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