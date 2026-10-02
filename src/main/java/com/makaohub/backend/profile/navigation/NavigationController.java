package com.makaohub.backend.profile.navigation;

import com.makaohub.backend.auth.exception.AuthException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me/navigation")
public class NavigationController {

    private final NavigationService navigationService;

    public NavigationController(
            NavigationService navigationService
    ) {
        this.navigationService = navigationService;
    }

    @GetMapping
    public NavigationResponse getNavigation(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return navigationService.getNavigation(
                extractUserId(jwt)
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

    private AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}