package com.makaohub.backend.messaging;

import com.makaohub.backend.messaging.api.AcknowledgeChatMessagesRequest;
import com.makaohub.backend.messaging.config.StompDestinationAuthorizationInterceptor;
import com.makaohub.backend.messaging.domain.RentalChatMessage;
import com.makaohub.backend.messaging.domain.RoommateChatMessage;
import com.makaohub.backend.messaging.service.ChatMessageRateLimiter;
import com.makaohub.backend.messaging.config.WebSocketSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatDeliveryTest {
    @Test void receivingHasAnIndependentRateLimitAndDoesNotConsumeSendingAllowance() {
        var properties = mock(WebSocketSecurityProperties.class);
        when(properties.messagesPerMinute()).thenReturn(1L);
        var limiter = new ChatMessageRateLimiter(properties);
        var user = UUID.randomUUID();
        limiter.requireDeliveryPermission(user);
        assertThrows(ResponseStatusException.class, () -> limiter.requireDeliveryPermission(user));
        limiter.requirePermission(user);
    }
    @Test void deliveryTimeStartsUnknownAndReplaysPreserveFirstAcknowledgement() {
        var rental = RentalChatMessage.clean(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Masked text");
        var roommate = RoommateChatMessage.clean(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Masked text");
        assertNull(rental.getDeliveredAt());
        assertNull(roommate.getDeliveredAt());
        var first = Instant.parse("2026-11-03T09:00:00Z");
        rental.confirmDelivery(first); roommate.confirmDelivery(first);
        rental.confirmDelivery(first.plusSeconds(60)); roommate.confirmDelivery(first.plusSeconds(60));
        assertEquals(first, rental.getDeliveredAt());
        assertEquals(first, roommate.getDeliveredAt());
    }

    @Test void requestRejectsInvalidBatchesAndLocksUniqueIdsInConsistentOrder() {
        var thread = UUID.randomUUID();
        var id = UUID.randomUUID();
        assertEquals(List.of(id), new AcknowledgeChatMessagesRequest(thread, List.of(id, id)).validatedIds());
        for (var request : List.of(new AcknowledgeChatMessagesRequest(null, List.of(id)),
                new AcknowledgeChatMessagesRequest(thread, null), new AcknowledgeChatMessagesRequest(thread, List.of()),
                new AcknowledgeChatMessagesRequest(thread, Collections.nCopies(101, id)),
                new AcknowledgeChatMessagesRequest(thread, Arrays.asList(id, null)))) {
            assertThrows(ResponseStatusException.class, request::validatedIds);
        }
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertEquals(ids.stream().sorted().toList(), new AcknowledgeChatMessagesRequest(thread, ids).validatedIds());
    }

    @Test void onlyAuthenticatedDeliveryHandlersAreAllowedNeverClientWritesToBrokerQueues() {
        var interceptor = new StompDestinationAuthorizationInterceptor();
        for (var route : List.of("/app/chat.delivered", "/app/rental-chat.delivered")) {
            var headers = StompHeaderAccessor.create(StompCommand.SEND);
            headers.setLeaveMutable(true);
            headers.setDestination(route);
            assertThrows(AccessDeniedException.class,
                    () -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null));
            headers.setUser(UsernamePasswordAuthenticationToken.authenticated("synthetic-user", null, List.of()));
            assertNotNull(interceptor.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null));
            headers.setDestination("/user/queue/chat.messages");
            assertThrows(AccessDeniedException.class,
                    () -> interceptor.preSend(MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()), null));
        }
    }
}
