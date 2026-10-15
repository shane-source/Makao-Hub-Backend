package com.makaohub.backend.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "auth_otp_challenges")
public class AuthOtpChallenge {
    public static final int MAX_ATTEMPTS = 5;
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private AuthOtpChallengePurpose purpose;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private AuthOtpChallengeStatus status;
    @Column(name = "authorization_version", nullable = false) private long authorizationVersion;
    @Column(name = "issued_at", nullable = false, updatable = false) private Instant issuedAt;
    @Column(name = "expires_at", nullable = false, updatable = false) private Instant expiresAt;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "code_digest", length = 64) private String codeDigest;
    @Column(name = "verified_at") private Instant verifiedAt;
    protected AuthOtpChallenge() { }
    public AuthOtpChallenge(User user, AuthOtpChallengePurpose purpose, long authorizationVersion, Instant issuedAt, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.user = Objects.requireNonNull(user); this.purpose = Objects.requireNonNull(purpose);
        this.status = AuthOtpChallengeStatus.PENDING; this.authorizationVersion = authorizationVersion;
        this.issuedAt = Objects.requireNonNull(issuedAt); this.expiresAt = Objects.requireNonNull(expiresAt);
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("OTP expiry must be after issue time.");
    }
    public void setCodeDigest(String digest) {
        if (codeDigest != null || status != AuthOtpChallengeStatus.PENDING || digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid OTP digest assignment.");
        }
        codeDigest = digest;
    }
    public boolean isUsable(Instant now) { return codeDigest != null && status == AuthOtpChallengeStatus.PENDING && expiresAt.isAfter(now) && attemptCount < MAX_ATTEMPTS; }
    public void recordFailedAttempt(Instant now) {
        if (status != AuthOtpChallengeStatus.PENDING) return;
        attemptCount = Math.incrementExact(attemptCount);
        if (attemptCount >= MAX_ATTEMPTS) status = AuthOtpChallengeStatus.LOCKED;
        else if (!expiresAt.isAfter(now)) status = AuthOtpChallengeStatus.EXPIRED;
    }
    public void markVerified(Instant now) { if (!isUsable(now)) throw new IllegalStateException("OTP challenge is not usable."); status = AuthOtpChallengeStatus.VERIFIED; verifiedAt = now; }
    public void markExpired(Instant now) { if (status == AuthOtpChallengeStatus.PENDING && !expiresAt.isAfter(now)) status = AuthOtpChallengeStatus.EXPIRED; }
    public void supersede() { if (status == AuthOtpChallengeStatus.PENDING) status = AuthOtpChallengeStatus.EXPIRED; }
    public UUID getId() { return id; }
    public User getUser() { return user; }
    public AuthOtpChallengePurpose getPurpose() { return purpose; }
    public AuthOtpChallengeStatus getStatus() { return status; }
    public long getAuthorizationVersion() { return authorizationVersion; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public int getAttemptCount() { return attemptCount; }
    public String getCodeDigest() { return codeDigest; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
