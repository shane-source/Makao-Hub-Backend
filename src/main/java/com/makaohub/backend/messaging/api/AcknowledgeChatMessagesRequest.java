package com.makaohub.backend.messaging.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Identifies messages received by the authenticated app, never a client-chosen recipient or timestamp. */
public record AcknowledgeChatMessagesRequest(
        @NotNull UUID threadId,
        @NotNull @Size(min = 1, max = 100) List<@NotNull UUID> messageIds
) {
    public List<UUID> validatedIds() {
        if (threadId == null || messageIds == null || messageIds.isEmpty() || messageIds.size() > 100
                || messageIds.stream().anyMatch(id -> id == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid delivery acknowledgement.");
        }
        // Consistent lock order prevents competing batches deadlocking; duplicate ACKs are harmless.
        return messageIds.stream().distinct().sorted().toList();
    }
}
