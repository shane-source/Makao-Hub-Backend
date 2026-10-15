package com.makaohub.backend.messaging.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Presence information for one authorized Renter-Landlord chat.
 *
 * It describes only the other participant in the requested thread.
 * It never exposes that participant's permanent user identifier or
 * provides a global list of online users.
 */
public record RentalPresenceResponse(

        /**
         * The rental chat whose presence was requested.
         */
        UUID threadId,

        /**
         * True when the other participant has at least one active,
         * authenticated WebSocket connection.
         */
        boolean otherParticipantOnline

) {

    /**
     * Prevents the backend from creating an invalid response.
     */
    public RentalPresenceResponse {
        Objects.requireNonNull(
                threadId,
                "Thread ID is required."
        );
    }
}