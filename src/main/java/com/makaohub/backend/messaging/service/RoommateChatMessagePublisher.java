package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.api.RoommateChatMessageResponse;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;
import java.util.UUID;

@Service
public class RoommateChatMessagePublisher {

    private static final String DESTINATION =
            "/queue/chat.messages";

    private final RoommatePresenceRegistry registry;
    private final RoommatePresenceService accessService;
    private final JwtDecoder jwtDecoder;
    private final SimpUserRegistry userRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    public RoommateChatMessagePublisher(
            RoommatePresenceRegistry registry,
            RoommatePresenceService accessService,
            JwtDecoder jwtDecoder,
            SimpUserRegistry userRegistry,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.registry = registry;
        this.accessService = accessService;
        this.jwtDecoder = jwtDecoder;
        this.userRegistry = userRegistry;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Attempts delivery to every currently registered connection
     * belonging to the Renter and Sublessor.
     *
     * The returned number is how many messages were submitted to
     * Spring's broker. It is not an acknowledgement from the clients.
     */
    public int publish(
            RoommateChatMessageService.SentMessage sent
    ) {
        Objects.requireNonNull(
                sent,
                "Sent message is required."
        );

        Objects.requireNonNull(
                sent.message(),
                "Message is required."
        );

        int submitted = publishToParticipant(
                sent.renterId(),
                sent.message()
        );

        submitted += publishToParticipant(
                sent.sublessorId(),
                sent.message()
        );

        return submitted;
    }

    /**
     * Checks every server-recorded connection for one participant.
     */
    private int publishToParticipant(
            UUID expectedRecipientId,
            RoommateChatMessageResponse message
    ) {
        if (expectedRecipientId == null) {
            return 0;
        }

        int submitted = 0;

        for (RoommatePresenceRegistry.AuthenticatedSession session
                : registry.sessionsFor(expectedRecipientId)) {
            if (publishToSession(
                    expectedRecipientId,
                    message,
                    session
            )) {
                submitted++;
            }
        }

        return submitted;
    }

    /**
     * Reauthorizes one connection immediately before delivery.
     */
    private boolean publishToSession(
            UUID expectedRecipientId,
            RoommateChatMessageResponse message,
            RoommatePresenceRegistry.AuthenticatedSession session
    ) {
        Jwt verified;
        UUID tokenUserId;

        try {
            /*
             * The configured decoder checks signature, expiration,
             * issuer, audience and the current authorization version.
             */
            verified = jwtDecoder.decode(
                    session.jwt().getTokenValue()
            );

            tokenUserId = UUID.fromString(
                    verified.getSubject()
            );
        } catch (
                JwtException
                | IllegalArgumentException
                | NullPointerException exception
        ) {
            return false;
        }

        /*
         * Prevents a stored session from being used to deliver data
         * to a different account.
         */
        if (!expectedRecipientId.equals(tokenUserId)) {
            return false;
        }

        /*
         * Confirm that Spring still considers this exact connection
         * active for the recipient.
         */
        SimpUser user = userRegistry.getUser(
                expectedRecipientId.toString()
        );

        if (user == null
                || user.getSession(session.sessionId()) == null) {
            return false;
        }

        try {
            /*
             * Reuses the complete participant, account, role,
             * verification, open-thread and paid-entitlement checks.
             *
             * Subscription state alone never authorizes delivery.
             */
            accessService.getOtherParticipantPresence(
                    expectedRecipientId,
                    message.threadId()
            );
        } catch (AuthException exception) {
            return false;
        } catch (ResponseStatusException exception) {
            int status = exception.getStatusCode().value();

            if (status == 401
                    || status == 403
                    || status == 404) {
                return false;
            }

            /*
             * Unexpected server or database failures remain visible.
             */
            throw exception;
        }

        SimpMessageHeaderAccessor headers =
                SimpMessageHeaderAccessor.create(
                        SimpMessageType.MESSAGE
                );

        headers.setSessionId(session.sessionId());
        headers.setLeaveMutable(true);

        /*
         * The session ID and matching header restrict delivery to
         * the connection that was just reauthorized.
         */
        messagingTemplate.convertAndSendToUser(
                session.sessionId(),
                DESTINATION,
                message,
                headers.getMessageHeaders()
        );

        return true;
    }
}