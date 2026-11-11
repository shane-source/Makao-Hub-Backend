package com.makaohub.backend.messaging;

import com.makaohub.backend.messaging.api.RentalChatThreadResponse;
import com.makaohub.backend.messaging.domain.ChatThreadStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RentalChatThreadResponseTest {

    @Test
    void exposesBothParticipantNamesInTheThreadSummary() {
        var response = new RentalChatThreadResponse(
                UUID.randomUUID(), UUID.randomUUID(), ChatThreadStatus.OPEN,
                Instant.EPOCH, Instant.EPOCH, null, "Renter Example", "Landlord Example"
        );

        assertEquals("Renter Example", response.renterName());
        assertEquals("Landlord Example", response.landlordName());
    }
}
