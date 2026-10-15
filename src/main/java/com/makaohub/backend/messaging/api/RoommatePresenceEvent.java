package com.makaohub.backend.messaging.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Live presence update delivered through the authenticated user's
 * private STOMP queue.
 *
 * The event identifies the chat thread instead of exposing the other
 * participant's permanent account identifier.
 */
public record RoommatePresenceEvent(

        /**
         * The private roommate chat whose participant presence changed.
         */
        UUID threadId,

        /**
         * True when the other participant has at least one active,
         * authenticated WebSocket connection.
         */
        boolean otherParticipantOnline,

        /**
         * Server-generated time at which the presence change was
         * observed.
         */
        Instant changedAt

) {

    /**
     * Prevents malformed presence events from being created inside
     * the backend.
     */
    public RoommatePresenceEvent {
        Objects.requireNonNull(
                threadId,
                "threadId must not be null."
        );

        Objects.requireNonNull(
                changedAt,
                "changedAt must not be null."
        );
    }

    /**
     * Factory for an online notification.
     */
    public static RoommatePresenceEvent online(
            UUID threadId,
            Instant changedAt
    ) {
        return new RoommatePresenceEvent(
                threadId,
                true,
                changedAt
        );
    }

    /**
     * Factory for an offline notification.
     */
    public static RoommatePresenceEvent offline(
            UUID threadId,
            Instant changedAt
    ) {
        return new RoommatePresenceEvent(
                threadId,
                false,
                changedAt
        );
    }
}