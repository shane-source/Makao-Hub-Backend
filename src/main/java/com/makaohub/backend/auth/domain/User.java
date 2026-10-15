package com.makaohub.backend.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Column(name = "phone_ciphertext", nullable = false)
    private byte[] phoneCiphertext;

    @Column(
            name = "phone_lookup_hash",
            nullable = false,
            unique = true,
            length = 64
    )
    private String phoneLookupHash;

    @Column(name = "avatar_object_path", length = 255)
    private String avatarObjectPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 20)
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    @Column(name = "role_selection_required", nullable = false)
    private boolean roleSelectionRequired;

    @Column(name = "authorization_version", nullable = false)
    private long authorizationVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(
            String fullName,
            String email,
            String passwordHash,
            byte[] phoneCiphertext,
            String phoneLookupHash
    ) {
        this(
                fullName,
                email,
                phoneCiphertext,
                phoneLookupHash
        );

        this.passwordHash = Objects.requireNonNull(passwordHash);
    }

    public User(
            String fullName,
            String email,
            byte[] phoneCiphertext,
            String phoneLookupHash
    ) {
        this.fullName = normalizeFullName(fullName);

        this.email = Objects.requireNonNull(email)
                .trim()
                .toLowerCase(Locale.ROOT);

        this.phoneCiphertext = Objects
                .requireNonNull(phoneCiphertext)
                .clone();

        this.phoneLookupHash =
                Objects.requireNonNull(phoneLookupHash);
    }

    private static String normalizeFullName(String fullName) {
        return Objects.requireNonNull(fullName)
                .strip()
                .replaceAll("\\s+", " ");
    }

    public UUID getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public void changeFullName(String fullName) {
        this.fullName = normalizeFullName(fullName);
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = Objects.requireNonNull(passwordHash);
        invalidateAuthorization();
    }

    public byte[] getPhoneCiphertext() {
        return phoneCiphertext.clone();
    }

    public String getPhoneLookupHash() {
        return phoneLookupHash;
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public boolean isRoleSelectionRequired() {
        return roleSelectionRequired;
    }

    public long getAuthorizationVersion() {
        return authorizationVersion;
    }

    public void invalidateAuthorization() {
        authorizationVersion = Math.incrementExact(
                authorizationVersion
        );
    }

    public void completeRoleSelection() {
        if (!roleSelectionRequired) {
            throw new IllegalStateException(
                    "This account does not require a role choice."
            );
        }

        authorizationVersion = Math.incrementExact(
                authorizationVersion
        );

        roleSelectionRequired = false;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getAvatarObjectPath() {
        return avatarObjectPath;
    }

    public void changeAvatarObjectPath(String avatarObjectPath) {
        this.avatarObjectPath = Objects.requireNonNull(
                avatarObjectPath
        ).strip();
    }
}
