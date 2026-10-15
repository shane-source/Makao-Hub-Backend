package com.makaohub.backend.roommate.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.roommate.service.RenterRoommateInterestService;
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
@RequestMapping("/api/v1/renter/roommate-interests")
public class RenterRoommateInterestController {

    private final RenterRoommateInterestService interestService;

    public RenterRoommateInterestController(
            RenterRoommateInterestService interestService
    ) {
        this.interestService = interestService;
    }

    @GetMapping
    public List<RoommateInterestResponse> getMyInterests(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return interestService.findMine(
                extractUserId(jwt)
        );
    }

    @GetMapping("/{interestId}")
    public RoommateInterestResponse getInterest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID interestId
    ) {
        return interestService.findOne(
                extractUserId(jwt),
                interestId
        );
    }

    @PostMapping("/listing/{sublessorListingId}")
    @ResponseStatus(HttpStatus.CREATED)
    public RoommateInterestResponse expressInterest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID sublessorListingId
    ) {
        return interestService.expressInterest(
                extractUserId(jwt),
                sublessorListingId
        );
    }

    @DeleteMapping("/{interestId}")
    public RoommateInterestResponse withdrawInterest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID interestId
    ) {
        return interestService.withdraw(
                extractUserId(jwt),
                interestId
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

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}