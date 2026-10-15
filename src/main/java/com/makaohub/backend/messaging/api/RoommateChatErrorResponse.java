package com.makaohub.backend.messaging.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Sanitized WebSocket error delivered through the authenticated
 * user's private chat error queue.
 *
 * This response must never contain exception messages, SQL details,
 * tokens, document information, account IDs, or rejected message
 * content.
 */
public record RoommateChatErrorResponse(
        Code code,
        String message,
        Instant occurredAt
) {

    private static final int MAX_MESSAGE_LENGTH = 200;

    /**
     * Validates every error response created by the backend.
     */
    public RoommateChatErrorResponse {
        Objects.requireNonNull(
                code,
                "Error code is required."
        );

        Objects.requireNonNull(
                occurredAt,
                "Error timestamp is required."
        );

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException(
                    "Error message is required."
            );
        }

        message = message.strip();

        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "Error message cannot exceed "
                            + MAX_MESSAGE_LENGTH
                            + " characters."
            );
        }
    }

    /**
     * Stable machine-readable codes understood by the Android and
     * web clients.
     */
    public enum Code {

        /*
         * The submitted WebSocket payload failed validation.
         */
        INVALID_REQUEST,

        /*
         * The session has no valid authenticated account.
         */
        AUTHENTICATION_REQUIRED,

        /*
         * The authenticated user cannot perform the operation.
         */
        ACCESS_DENIED,

        /*
         * The sender exceeded the permitted message rate.
         */
        RATE_LIMITED,

        /*
         * Chat cannot currently be used. This intentionally does not
         * reveal whether the thread is missing, closed or locked.
         */
        CHAT_UNAVAILABLE,

        /*
         * An unexpected backend failure occurred.
         */
        INTERNAL_ERROR
    }
}