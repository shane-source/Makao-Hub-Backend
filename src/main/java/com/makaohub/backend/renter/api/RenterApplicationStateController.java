package com.makaohub.backend.renter.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.renter.service.RenterApplicationStateService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** A renter archives, deletes or restores an application in their own list. */
@RestController
@RequestMapping("/api/v1/renter/application-state")
public class RenterApplicationStateController {

    public record ChangeStateRequest(String state) {
    }

    private final RenterApplicationStateService service;

    public RenterApplicationStateController(RenterApplicationStateService service) {
        this.service = service;
    }

    @GetMapping
    public List<RenterApplicationStateService.ApplicationState> list(Authentication authentication) {
        return service.list(userId(authentication));
    }

    @PutMapping("/{kind}/{applicationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void change(
            Authentication authentication,
            @PathVariable String kind,
            @PathVariable UUID applicationId,
            @RequestBody ChangeStateRequest request
    ) {
        service.change(userId(authentication), kind, applicationId, request == null ? null : request.state());
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw unavailable();
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw unavailable();
        }
    }

    private static AuthException unavailable() {
        return new AuthException(AuthException.Reason.ACCOUNT_UNAVAILABLE, "The account is unavailable.");
    }
}
