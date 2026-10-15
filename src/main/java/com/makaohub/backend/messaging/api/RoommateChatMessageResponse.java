package com.makaohub.backend.messaging.api;

import java.time.Instant;
import java.util.UUID;

public record RoommateChatMessageResponse(
        UUID id,
        UUID threadId,
        UUID senderId,
        UUID clientMessageId,
        String content,
        boolean contactMasked,
        Instant createdAt
) {
}
