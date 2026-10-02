package com.makaohub.backend.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(
            name = "token_hash",
            nullable = false,
            unique = true,
            length = 64
    )
    private String tokenHash;

    @Column(
            name = "token_family_id",
            nullable = false,
            updatable = false
    )
    private UUID tokenFamilyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_token_id", unique = true)
    private RefreshToken replacedByToken;

    @CreationTimestamp
    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 50)
    private String revocationReason;

    protected RefreshToken() {
    }

    public RefreshToken(
            User user,
            String tokenHash,
            UUID tokenFamilyId,
            Instant expiresAt
    ) {
        this.user = Objects.requireNonNull(user);
        this.tokenHash = Objects.requireNonNull(tokenHash);
        this.tokenFamilyId = Objects.requireNonNull(tokenFamilyId);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void markUsed(
            RefreshToken replacement,
            Instant usedAt
    ) {
        if (isUsed()) {
            throw new IllegalStateException(
                    "Refresh token has already been used."
            );
        }

        this.replacedByToken =
                Objects.requireNonNull(replacement);
        this.usedAt = Objects.requireNonNull(usedAt);
    }

    public void revoke(
            Instant revokedAt,
            String reason
    ) {
        if (isRevoked()) {
            return;
        }

        this.revokedAt = Objects.requireNonNull(revokedAt);
        this.revocationReason =
                Objects.requireNonNull(reason);
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getTokenFamilyId() {
        return tokenFamilyId;
    }

    public RefreshToken getReplacedByToken() {
        return replacedByToken;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }
}