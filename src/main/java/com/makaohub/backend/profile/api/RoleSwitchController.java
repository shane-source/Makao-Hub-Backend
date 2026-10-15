package com.makaohub.backend.profile.api;

import com.makaohub.backend.auth.api.AuthTokensResponse;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.profile.service.RoleSwitchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile/me/role")
@ConditionalOnProperty(
        name = "makao.role-switch.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class RoleSwitchController {

    private final RoleSwitchService service;

    public RoleSwitchController(RoleSwitchService service) {
        this.service = service;
    }

    @PostMapping("/google-challenge")
    public ResponseEntity<GoogleRoleSwitchChallengeResponse> googleChallenge(
            @AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody ChallengeRequest request
    ) {
        GoogleRoleSwitchChallengeResponse response =
                service.issueGoogleChallenge(
                        principal,
                        request.targetRole()
                );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/switch")
    public ResponseEntity<AuthTokensResponse> switchRole(
            @AuthenticationPrincipal Jwt principal,
            @Valid @RequestBody SwitchRoleRequest request
    ) {
        AuthTokensResponse response = service.switchRole(
                principal,
                request
        );

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    public record ChallengeRequest(
            @NotNull(message = "Target role is required.")
            RoleName targetRole
    ) {
    }
}