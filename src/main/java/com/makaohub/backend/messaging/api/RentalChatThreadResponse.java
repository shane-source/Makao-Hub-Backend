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
        Instant closedAt
) {
}