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

/**
 * Stores one message in a Renter-Landlord conversation.
 *
 * The message service must authorize the sender and mask contact
 * information before creating this entity.
 */
@Entity
@Table(name = "rental_chat_messages")
public class RentalChatMessage {

    public static final int MAX_BODY_LENGTH = 2_000;

    private static final String CONTACT_DETAILS_REASON =
            "CONTACT_DETAILS";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(
            name = "chat_thread_id",
            nullable = false,
            updatable = false
    )
    private UUID chatThreadId;

    @Column(
            name = "sender_id",
            nullable = false,
            updatable = false
    )
    private UUID senderId;

    @Column(
            name = "client_message_id",
            nullable = false,
            updatable = false
    )
    private UUID clientMessageId;

    /*
     * Only text processed by ChatContactMaskingService belongs here.
     */
    @Column(
            name = "masked_body",
            nullable = false,
            updatable = false,
            columnDefinition = "TEXT"
    )
    private String maskedBody;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "moderation_status",
            nullable = false,
            updatable = false,
            length = 20
    )
    private MessageModerationStatus moderationStatus;

    @Column(
            name = "moderation_reason",
            updatable = false,
            length = 50
    )
    private String moderationReason;

    @CreationTimestamp
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private Instant createdAt;

    /**
     * Required by JPA when loading stored messages.
     */
    protected RentalChatMessage() {
    }

    /**
     * Accepts only backend-authorized identifiers and already-masked
     * content. The boolean comes from the masking service.
     */
    private RentalChatMessage(
            UUID chatThreadId,
            UUID senderId,
            UUID clientMessageId,
            String maskedBody,
            boolean contactMasked
    ) {
        this.chatThreadId = Objects.requireNonNull(
                chatThreadId,
                "Chat thread ID is required."
        );

        this.senderId = Objects.requireNonNull(
                senderId,
                "Sender ID is required."
        );

        this.clientMessageId = Objects.requireNonNull(
                clientMessageId,
                "Client message ID is required."
        );

        this.maskedBody = normalizeBody(maskedBody);

        this.moderationStatus = contactMasked
                ? MessageModerationStatus.FLAGGED
                : MessageModerationStatus.CLEAN;

        this.moderationReason = contactMasked
                ? CONTACT_DETAILS_REASON
                : null;
    }

    /**
     * Creates a message after the masking service detected no contact
     * information. This method does not perform detection itself.
     */
    public static RentalChatMessage clean(
            UUID chatThreadId,
            UUID senderId,
            UUID clientMessageId,
            String maskedBody
    ) {
        return new RentalChatMessage(
                chatThreadId,
                senderId,
                clientMessageId,
                maskedBody,
                false
        );
    }

    /**
     * Creates a message whose detected contact details were hidden.
     *
     * The reason is fixed so detected phone numbers or emails cannot
     * accidentally be passed into the moderation-reason field.
     */
    public static RentalChatMessage flagged(
            UUID chatThreadId,
            UUID senderId,
            UUID clientMessageId,
            String maskedBody
    ) {
        return new RentalChatMessage(
                chatThreadId,
                senderId,
                clientMessageId,
                maskedBody,
                true
        );
    }

    /**
     * Validates the stored text after masking.
     *
     * Uses the existing Java message-length convention:
     * a maximum of 2,000 UTF-16 code units.
     */
    private static String normalizeBody(String maskedBody) {
        if (maskedBody == null) {
            throw new IllegalArgumentException(
                    "Message body is required."
            );
        }

        if (maskedBody.length() > MAX_BODY_LENGTH) {
            throw new IllegalArgumentException(
                    "Message body cannot exceed "
                            + MAX_BODY_LENGTH
                            + " characters."
            );
        }

        String normalized = maskedBody.strip();

        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    "Message body cannot be empty."
            );
        }

        return normalized;
    }

    public UUID getId() {
        return id;
    }

    public UUID getChatThreadId() {
        return chatThreadId;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public UUID getClientMessageId() {
        return clientMessageId;
    }

    public String getMaskedBody() {
        return maskedBody;
    }

    public MessageModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public String getModerationReason() {
        return moderationReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isFlagged() {
        return moderationStatus == MessageModerationStatus.FLAGGED;
    }
}