package com.makaohub.backend.messaging.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Live presence update for one authorized Renter-Landlord chat.
 *
 * The event identifies the thread instead of exposing the other
 * participant's account or WebSocket session identifier.
 */
public record RentalPresenceEvent(
        UUID threadId,
        boolean otherParticipantOnline,
        Instant changedAt
) {

    public RentalPresenceEvent {
        Objects.requireNonNull(
                threadId,
                "Thread ID is required."
        );

        Objects.requireNonNull(
                changedAt,
                "Presence change time is required."
        );
    }
}
