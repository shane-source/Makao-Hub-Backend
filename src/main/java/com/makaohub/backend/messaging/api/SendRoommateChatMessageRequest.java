package com.makaohub.backend.messaging.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SendRoommateChatMessageRequest(
        @NotNull(message = "Thread ID is required.")
        UUID threadId,

        @NotNull(message = "Client message ID is required.")
        UUID clientMessageId,

        @NotBlank(message = "Message content is required.")
        @Size(max = 2000, message = "Messages cannot exceed 2000 characters.")
        String content
) {
}
