package com.makaohub.backend.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "mfa_totp_credentials")
public class TotpCredential {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "secret_ciphertext", nullable = false)
    private byte[] secretCiphertext;

    @Column(name = "secret_key_version", nullable = false)
    private short secretKeyVersion = 1;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "last_accepted_time_step")
    private Long lastAcceptedTimeStep;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TotpCredential() {
    }

    public TotpCredential(
            User user,
            byte[] secretCiphertext,
            short secretKeyVersion
    ) {
        this.user = Objects.requireNonNull(user);
        this.secretCiphertext = Objects
                .requireNonNull(secretCiphertext)
                .clone();
        this.secretKeyVersion = secretKeyVersion;
    }

    public void enable(Instant verifiedAt) {
        this.verifiedAt = Objects.requireNonNull(verifiedAt);
        this.enabled = true;
    }

    public void recordAcceptedTimeStep(long timeStep) {
        if (timeStep < 0) {
            throw new IllegalArgumentException(
                    "TOTP time step cannot be negative."
            );
        }

        if (lastAcceptedTimeStep != null
                && timeStep <= lastAcceptedTimeStep) {
            throw new IllegalStateException(
                    "TOTP code has already been used."
            );
        }

        this.lastAcceptedTimeStep = timeStep;
    }

    public UUID getUserId() {
        return userId;
    }

    public User getUser() {
        return user;
    }

    public byte[] getSecretCiphertext() {
        return secretCiphertext.clone();
    }

    public short getSecretKeyVersion() {
        return secretKeyVersion;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Long getLastAcceptedTimeStep() {
        return lastAcceptedTimeStep;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}