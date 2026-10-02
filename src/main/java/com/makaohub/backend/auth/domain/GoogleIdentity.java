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

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "google_identities")
public class GoogleIdentity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(
            name = "google_subject",
            nullable = false,
            unique = true,
            length = 255
    )
    private String googleSubject;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GoogleIdentity() {
    }

    public GoogleIdentity(User user, String googleSubject) {
        this.user = Objects.requireNonNull(user);

        if (googleSubject == null || googleSubject.isBlank()) {
            throw new IllegalArgumentException(
                    "Google subject cannot be blank."
            );
        }

        this.googleSubject = googleSubject;
    }

    public UUID getUserId() {
        return userId;
    }

    public User getUser() {
        return user;
    }

    public String getGoogleSubject() {
        return googleSubject;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}