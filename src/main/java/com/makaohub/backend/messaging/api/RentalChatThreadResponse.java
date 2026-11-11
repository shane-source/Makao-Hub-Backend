package com.makaohub.backend.messaging.api;

import com.makaohub.backend.messaging.domain.ChatThreadStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Client-facing summary of an authorized Renter-Landlord conversation.
 *
 * The service must check role and ownership before returning it.
 */
public record RentalChatThreadResponse(
        UUID id,
        UUID rentalApplicationId,
        ChatThreadStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        String renterName,
        String landlordName,
        String counterpartyName,
        String counterpartyAvatarUrl,
        String lastMessage,
        Instant lastMessageAt
) {
    public RentalChatThreadResponse(UUID id, UUID rentalApplicationId, ChatThreadStatus status, Instant createdAt,
            Instant updatedAt, Instant closedAt, String renterName, String landlordName) {
        this(id, rentalApplicationId, status, createdAt, updatedAt, closedAt, renterName, landlordName, null, null, null, null);
    }
    @Override public String toString() { return "RentalChatThreadResponse[redacted]"; }
}
