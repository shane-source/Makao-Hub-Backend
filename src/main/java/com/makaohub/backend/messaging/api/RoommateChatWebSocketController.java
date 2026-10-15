package com.makaohub.backend.messaging.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.service.RoommateChatMessagePublisher;
import com.makaohub.backend.messaging.service.RoommateChatMessageService;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class RoommateChatWebSocketController {

    private final RoommateChatMessageService messageService;
    private final RoommateChatMessagePublisher messagePublisher;

    public RoommateChatWebSocketController(
            RoommateChatMessageService messageService,
            RoommateChatMessagePublisher messagePublisher
    ) {
        this.messageService = messageService;
        this.messagePublisher = messagePublisher;
    }

    @MessageMapping("/chat.send")
    public void send(
            Authentication authentication,
            @Valid SendRoommateChatMessageRequest request
    ) {
        UUID authenticatedUserId = extractUserId(authentication);

        RoommateChatMessageService.SentMessage sent =
                messageService.send(
                        authenticatedUserId,
                        request
                );

        messagePublisher.publish(sent);
    }

    private static UUID extractUserId(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()) {
            throw accountUnavailable();
        }

        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw accountUnavailable();
        }
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }
}