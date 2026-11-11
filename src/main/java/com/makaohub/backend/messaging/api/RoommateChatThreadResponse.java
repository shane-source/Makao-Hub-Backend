package com.makaohub.backend.messaging.api;

import com.makaohub.backend.messaging.domain.RoommateChatThreadStatus;

import java.time.Instant;
import java.util.UUID;

public record RoommateChatThreadResponse(
        UUID id,
        UUID roommateInterestId,
        RoommateChatThreadStatus status,
        boolean chatUnlocked,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt,
        String counterpartyName,
        String counterpartyAvatarUrl,
        String lastMessage,
        Instant lastMessageAt
) {
    public RoommateChatThreadResponse(UUID id, UUID roommateInterestId, RoommateChatThreadStatus status, boolean chatUnlocked,
            Instant createdAt, Instant updatedAt, Instant closedAt) {
        this(id, roommateInterestId, status, chatUnlocked, createdAt, updatedAt, closedAt, null, null, null, null);
    }
    @Override public String toString() { return "RoommateChatThreadResponse[redacted]"; }
}
