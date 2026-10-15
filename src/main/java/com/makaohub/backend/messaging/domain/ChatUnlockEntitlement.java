package com.makaohub.backend.messaging.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "chat_unlock_entitlements")
public class ChatUnlockEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chat_thread_id", nullable = false)
    private UUID chatThreadId;

    @Column(name = "payer_user_id", nullable = false)
    private UUID payerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChatUnlockStatus status =
            ChatUnlockStatus.PENDING;

    @Column(name = "amount_minor", nullable = false)
    private Long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(
            name = "idempotency_key",
            nullable = false,
            length = 100
    )
    private String idempotencyKey;

    @Column(
            name = "provider_payment_reference",
            unique = true,
            length = 120
    )
    private String providerPaymentReference;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ChatUnlockEntitlement() {
    }

    public ChatUnlockEntitlement(
            UUID chatThreadId,
            UUID payerUserId,
            Long amountMinor,
            String currency,
            String idempotencyKey
    ) {
        this.chatThreadId =
                Objects.requireNonNull(chatThreadId);

        this.payerUserId =
                Objects.requireNonNull(payerUserId);

        if (amountMinor == null || amountMinor <= 0) {
            throw new IllegalArgumentException(
                    "Chat unlock amount must be greater than zero."
            );
        }

        this.amountMinor = amountMinor;

        this.currency =
                Objects.requireNonNull(currency)
                        .trim()
                        .toUpperCase();

        if (this.currency.length() != 3) {
            throw new IllegalArgumentException(
                    "Currency must be a three-letter code."
            );
        }

        this.idempotencyKey =
                Objects.requireNonNull(idempotencyKey)
                        .trim();

        if (this.idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Idempotency key is required."
            );
        }
    }

    public void confirm(
            String providerPaymentReference,
            Instant confirmedAt
    ) {
        requireStatus(ChatUnlockStatus.PENDING);

        this.providerPaymentReference =
                Objects.requireNonNull(
                        providerPaymentReference
                ).trim();

        if (this.providerPaymentReference.isBlank()) {
            throw new IllegalArgumentException(
                    "Provider payment reference is required."
            );
        }

        this.confirmedAt =
                Objects.requireNonNull(confirmedAt);

        this.status = ChatUnlockStatus.CONFIRMED;
    }

    public void markFailed() {
        requireStatus(ChatUnlockStatus.PENDING);
        status = ChatUnlockStatus.FAILED;
    }

    public void revoke(Instant revokedAt) {
        requireStatus(ChatUnlockStatus.CONFIRMED);

        this.revokedAt =
                Objects.requireNonNull(revokedAt);

        this.status = ChatUnlockStatus.REVOKED;
    }

    public void refund() {
        requireStatus(ChatUnlockStatus.CONFIRMED);
        status = ChatUnlockStatus.REFUNDED;
    }

    private void requireStatus(
            ChatUnlockStatus expectedStatus
    ) {
        if (status != expectedStatus) {
            throw new IllegalStateException(
                    "Invalid chat unlock status transition."
            );
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getChatThreadId() {
        return chatThreadId;
    }

    public UUID getPayerUserId() {
        return payerUserId;
    }

    public ChatUnlockStatus getStatus() {
        return status;
    }

    public Long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getProviderPaymentReference() {
        return providerPaymentReference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}