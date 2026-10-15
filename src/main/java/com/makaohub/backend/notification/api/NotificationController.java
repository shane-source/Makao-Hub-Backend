package com.makaohub.backend.notification.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.notification.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(
            NotificationService notificationService
    ) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public List<NotificationResponse> getMyNotifications(
            @AuthenticationPrincipal Jwt jwt
    ) {
        return notificationService.findMine(
                extractUserId(jwt)
        );
    }

    @PatchMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID notificationId
    ) {
        notificationService.markRead(
                extractUserId(jwt),
                notificationId
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