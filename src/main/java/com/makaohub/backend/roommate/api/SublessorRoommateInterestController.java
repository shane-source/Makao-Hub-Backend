package com.makaohub.backend.roommate.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.roommate.service.SublessorRoommateInterestReviewService;
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
@RequestMapping("/api/v1/sublessor/roommate-interests")
public class SublessorRoommateInterestController {

    private final SublessorRoommateInterestReviewService interestService;

    public SublessorRoommateInterestController(
            SublessorRoommateInterestReviewService interestService
    ) {
        this.interestService = interestService;
    }

    @GetMapping
    public List<RoommateInterestResponse> getInterestsForMyListings(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return interestService.findForMyListings(
                extractUserId(jwt)
        );
    }

    @PostMapping("/{interestId}/review")
    public RoommateInterestResponse markUnderReview(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID interestId
    ) {
        return interestService.markUnderReview(
                extractUserId(jwt),
                interestId
        );
    }

    @PostMapping("/{interestId}/accept")
    public RoommateInterestResponse acceptInterest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID interestId
    ) {
        return interestService.accept(
                extractUserId(jwt),
                interestId
        );
    }

    @PostMapping("/{interestId}/decline")
    public RoommateInterestResponse declineInterest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID interestId
    ) {
        return interestService.decline(
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