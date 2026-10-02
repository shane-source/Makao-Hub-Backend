package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.JwtSecurityProperties;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class AccessTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtSecurityProperties properties;
    private final Clock clock;

    public AccessTokenService(
            JwtEncoder jwtEncoder,
            JwtSecurityProperties properties,
            Clock clock
    ) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedAccessToken issue(
            User user,
            Collection<RoleName> roles
    ) {
        Objects.requireNonNull(user, "User cannot be null.");
        Objects.requireNonNull(roles, "Roles cannot be null.");

        if (roles.isEmpty()) {
            throw new IllegalArgumentException(
                    "An access token requires at least one role."
            );
        }

        Instant issuedAt = Instant.now(clock);
        Instant expiresAt = issuedAt.plus(
                properties.accessTokenTtl()
        );

        List<String> roleClaims = roles.stream()
                .map(RoleName::name)
                .sorted()
                .toList();

        JwsHeader headers = JwsHeader
                .with(SignatureAlgorithm.RS256)
                .keyId(properties.keyId())
                .build();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .audience(List.of(properties.audience()))
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("token_type", "access")
                .claim("roles", roleClaims)
                .build();

        Jwt jwt = jwtEncoder.encode(
                JwtEncoderParameters.from(headers, claims)
        );

        return new IssuedAccessToken(
                jwt.getTokenValue(),
                expiresAt,
                properties.accessTokenTtl().toSeconds()
        );
    }

    public record IssuedAccessToken(
            String value,
            Instant expiresAt,
            long expiresInSeconds
    ) {
    }
}