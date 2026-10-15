package com.makaohub.backend.messaging.api;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.service.RentalChatMessagePublisher;
import com.makaohub.backend.messaging.service.RentalChatMessageService;
import jakarta.validation.Valid;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
public class RentalChatWebSocketController {

    private final RentalChatMessageService messageService;
    private final RentalChatMessagePublisher messagePublisher;

    public RentalChatWebSocketController(
            RentalChatMessageService messageService,
            RentalChatMessagePublisher messagePublisher
    ) {
        this.messageService = messageService;
        this.messagePublisher = messagePublisher;
    }

    @MessageMapping("/rental-chat.send")
    public void send(
            Authentication authentication,
            @Valid SendRentalChatMessageRequest request
    ) {
        UUID authenticatedUserId = extractUserId(authentication);

        /*
         * The service checks access, masks contacts and saves the
         * message. Its transaction completes before this call returns.
         */
        RentalChatMessageService.SentMessage sent =
                messageService.send(
                        authenticatedUserId,
                        request
                );

        /*
         * Publish only the saved response, never the original
         * client-supplied message.
         */
        messagePublisher.publish(sent);
    }

    private static UUID extractUserId(
            Authentication authentication
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()) {
            throw accountUnavailable();
        }

        String subject = authentication.getName();

        if (subject == null || subject.isBlank()) {
            throw accountUnavailable();
        }

        try {
            return UUID.fromString(subject);
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