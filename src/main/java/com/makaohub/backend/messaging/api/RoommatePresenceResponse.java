package com.makaohub.backend.messaging.api;

import java.util.UUID;

/**
 * Presence information for one authorized chat thread.
 *
 * The response describes only the other participant in the requested
 * thread. It never provides a global list of online Makao Hub users.
 */
public record RoommatePresenceResponse(

        /**
         * The chat thread for which presence was requested.
         */
        UUID threadId,

        /**
         * True when the other participant has at least one active,
         * authenticated WebSocket session.
         */
        boolean otherParticipantOnline

) {
}