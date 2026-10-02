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
@Table(name = "mfa_login_challenges")
public class MfaLoginChallenge {

    private static final short MAX_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "attempt_count", nullable = false)
    private short attemptCount;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MfaLoginChallenge() {
    }

    public MfaLoginChallenge(
            User user,
            Instant expiresAt
    ) {
        this.user = Objects.requireNonNull(user);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }

    public boolean hasAttemptsRemaining() {
        return attemptCount < MAX_ATTEMPTS;
    }

    public boolean canBeUsed(Instant now) {
        return !isConsumed()
                && !isExpired(now)
                && hasAttemptsRemaining();
    }

    public void recordFailedAttempt() {
        if (!hasAttemptsRemaining()) {
            throw new IllegalStateException(
                    "MFA challenge has no attempts remaining."
            );
        }

        attemptCount++;
    }

    public void consume(Instant consumedAt) {
        if (isConsumed()) {
            throw new IllegalStateException(
                    "MFA challenge has already been consumed."
            );
        }

        this.consumedAt = Objects.requireNonNull(consumedAt);
    }

    public UUID getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public short getAttemptCount() {
        return attemptCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}