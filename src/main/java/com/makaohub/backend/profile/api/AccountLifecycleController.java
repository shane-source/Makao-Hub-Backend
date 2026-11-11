package com.makaohub.backend.profile.api;

import com.makaohub.backend.auth.api.OtpChallengeResponse;
import com.makaohub.backend.auth.domain.AuthOtpChallengePurpose;
import com.makaohub.backend.profile.service.AccountLifecycleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/profile/me/account-action")
public class AccountLifecycleController {
    private final AccountLifecycleService lifecycle;

    public AccountLifecycleController(AccountLifecycleService lifecycle) { this.lifecycle = lifecycle; }

    public enum Action { DEACTIVATE, DELETE }
    public record Start(@NotBlank @Email @Size(max=320) String email, Action action) { }
    public record Confirm(@NotBlank @Email @Size(max=320) String email, Action action,
                          @NotBlank String challengeId, @NotBlank @Pattern(regexp="[0-9]{6}") String code) { }

    @PostMapping("/start")
    public OtpChallengeResponse start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Start request) {
        return lifecycle.start(subject(jwt), request.email(), purpose(request.action()));
    }

    @PostMapping("/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirm(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Confirm request) {
        UUID challengeId;
        try { challengeId = UUID.fromString(request.challengeId()); }
        catch (IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        lifecycle.confirm(subject(jwt), request.email(), challengeId, request.code(), purpose(request.action()));
    }

    private AuthOtpChallengePurpose purpose(Action action) {
        if (action == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return action == Action.DELETE ? AuthOtpChallengePurpose.ACCOUNT_DELETION
                : AuthOtpChallengePurpose.ACCOUNT_DEACTIVATION;
    }

    private UUID subject(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException failure) { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    }
}
