package com.makaohub.backend.auth.config;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
public class CurrentAccountJwtValidator implements OAuth2TokenValidator<Jwt> {
    private static final OAuth2Error INVALID = new OAuth2Error(
            "invalid_token", "The access token is no longer valid. Sign in again.", null);
    private final UserRoleRepository userRoles;

    public CurrentAccountJwtValidator(UserRoleRepository userRoles) {
        this.userRoles = userRoles;
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        UUID userId;
        RoleName role;
        long version;
        try {
            if (!"access".equals(jwt.getClaimAsString("token_type"))) return invalid();
            userId = UUID.fromString(jwt.getSubject());
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null || roles.size() != 1) return invalid();
            role = RoleName.valueOf(roles.getFirst());
            Object rawVersion = jwt.getClaims().get("authorization_version");
            // Existing signed tokens predate this claim. Only unchanged version-zero accounts accept them.
            if (rawVersion == null) version = 0;
            else if (rawVersion instanceof Long value) version = value;
            else if (rawVersion instanceof Integer value) version = value.longValue();
            else return invalid();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return invalid();
        }
        // One statement observes role, account restrictions and token version together.
        if (!userRoles.isCurrentAuthorization(userId, role, version)) {
            return invalid();
        }
        return OAuth2TokenValidatorResult.success();
    }

    private OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(INVALID);
    }
}
