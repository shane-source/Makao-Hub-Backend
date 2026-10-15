package com.makaohub.backend.messaging.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Client-facing message returned through authorized history requests
 * and private WebSocket delivery.
 *
 * content must contain the stored, masked message text.
 */
public record RentalChatMessageResponse(
        UUID id,
        UUID threadId,
        UUID senderId,
        UUID clientMessageId,
        String content,
        boolean contactMasked,
        Instant createdAt
) {
}