package com.makaohub.backend.renter.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.renter.service.SublessorRentalApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/renter/sublessor-applications")
public class SublessorRentalApplicationController {

    private final SublessorRentalApplicationService applicationService;

    public SublessorRentalApplicationController(
            SublessorRentalApplicationService applicationService
    ) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public List<RentalApplicationResponse> getMyApplications(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return applicationService.findMine(
                extractUserId(jwt)
        );
    }

    @GetMapping("/{applicationId}")
    public RentalApplicationResponse getApplication(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        return applicationService.findOne(
                extractUserId(jwt),
                applicationId
        );
    }

    @PostMapping("/listing/{listingId}")
    @ResponseStatus(HttpStatus.CREATED)
    public RentalApplicationResponse apply(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID listingId
    ) {
        return applicationService.apply(
                extractUserId(jwt),
                listingId
        );
    }

    @DeleteMapping("/{applicationId}")
    public RentalApplicationResponse withdraw(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID applicationId
    ) {
        return applicationService.withdraw(
                extractUserId(jwt),
                applicationId
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