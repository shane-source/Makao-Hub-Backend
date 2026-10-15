package com.makaohub.backend.profile.domain;

import com.makaohub.backend.auth.domain.RoleName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "role_switch_challenges")
public class RoleSwitchChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "target_role",
            nullable = false,
            updatable = false,
            length = 40
    )
    private RoleName targetRole;

    @Column(
            name = "authorization_version",
            nullable = false,
            updatable = false
    )
    private long authorizationVersion;

    @Column(
            name = "nonce_hash",
            nullable = false,
            updatable = false,
            length = 64
    )
    private String nonceHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected RoleSwitchChallenge() {
    }

    public RoleSwitchChallenge(
            UUID userId,
            RoleName targetRole,
            long authorizationVersion,
            String nonceHash,
            Instant issuedAt,
            Instant expiresAt
    ) {
        this.userId = Objects.requireNonNull(userId);
        this.targetRole = Objects.requireNonNull(targetRole);
        this.nonceHash = Objects.requireNonNull(nonceHash);
        this.issuedAt = Objects.requireNonNull(issuedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);

        if (targetRole != RoleName.LANDLORD
                && targetRole != RoleName.RENTER
                && targetRole != RoleName.SUBLESSOR) {
            throw new IllegalArgumentException(
                    "A public target role is required."
            );
        }

        if (authorizationVersion < 0) {
            throw new IllegalArgumentException(
                    "Authorization version cannot be negative."
            );
        }

        if (!nonceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "A SHA-256 hexadecimal nonce hash is required."
            );
        }

        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException(
                    "Challenge expiry must follow issuance."
            );
        }

        this.authorizationVersion = authorizationVersion;
    }

    public UUID getId() {
        return id;
    }
}