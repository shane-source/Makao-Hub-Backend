package com.makaohub.backend.messaging.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class StompDestinationAuthorizationInterceptor
        implements ChannelInterceptor {

    private static final Set<String> ALLOWED_SEND_DESTINATIONS =
            Set.of(
                    "/app/chat.send",
                    "/app/rental-chat.send"
            );

    private static final Set<String> ALLOWED_SUBSCRIBE_DESTINATIONS =
            Set.of(
                    "/user/queue/chat.messages",
                    "/user/queue/chat.presence",
                    "/user/queue/chat.errors",
                    "/user/queue/rental-chat.messages",
                    "/user/queue/rental-chat.presence"
            );

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {
        StompHeaderAccessor accessor =
                StompHeaderAccessor.wrap(message);

        StompCommand command = accessor.getCommand();

        /*
         * Internal broker messages and heartbeat frames may not carry
         * a STOMP command.
         */
        if (command == null) {
            return message;
        }

        requireAuthenticated(accessor);

        /*
         * These commands do not publish application data.
         */
        if (command == StompCommand.CONNECT
                || command == StompCommand.DISCONNECT
                || command == StompCommand.UNSUBSCRIBE) {
            return message;
        }

        String destination = accessor.getDestination();

        if (destination == null || destination.isBlank()) {
            throw new AccessDeniedException(
                    "A WebSocket destination is required."
            );
        }

        /*
         * Clients may only send to registered application handlers.
         * They cannot send directly to private broker queues.
         */
        if (command == StompCommand.SEND
                && ALLOWED_SEND_DESTINATIONS.contains(destination)) {
            return message;
        }

        /*
         * Clients may only subscribe to approved private queues.
         */
        if (command == StompCommand.SUBSCRIBE
                && ALLOWED_SUBSCRIBE_DESTINATIONS.contains(destination)) {
            return message;
        }

        throw new AccessDeniedException(
                "The WebSocket destination is not permitted."
        );
    }

    private static void requireAuthenticated(
            StompHeaderAccessor accessor
    ) {
        if (!(accessor.getUser()
                instanceof Authentication authentication)
                || !authentication.isAuthenticated()) {
            throw new AccessDeniedException(
                    "WebSocket authentication is required."
            );
        }
    }
}