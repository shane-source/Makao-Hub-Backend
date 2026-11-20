package com.makaohub.backend.profile.api;

import com.makaohub.backend.profile.service.SessionRevocationService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profile/me/sessions")
public class SessionRevocationController {
    private final SessionRevocationService sessions;

    public SessionRevocationController(SessionRevocationService sessions) {
        this.sessions = sessions;
    }

    /** "Sign out everywhere": every refresh token of the caller is revoked. Access tokens lapse within their short TTL. */
    @PostMapping("/revoke-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeAll(@AuthenticationPrincipal Jwt jwt) {
        UUID userId;
        try { userId = UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException failure) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
        sessions.revokeAll(userId);
    }
}
