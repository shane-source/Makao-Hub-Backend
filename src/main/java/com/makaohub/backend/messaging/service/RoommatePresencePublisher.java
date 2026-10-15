package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.api.RoommatePresenceEvent;
import com.makaohub.backend.messaging.api.RoommatePresenceResponse;
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

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
public class RoommatePresencePublisher {

    private static final String DESTINATION =
            "/queue/chat.presence";

    private final RoommatePresenceService presenceService;
    private final JwtDecoder jwtDecoder;
    private final SimpUserRegistry userRegistry;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public RoommatePresencePublisher(
            RoommatePresenceService presenceService,
            JwtDecoder jwtDecoder,
            SimpUserRegistry userRegistry,
            SimpMessagingTemplate messagingTemplate,
            Clock clock
    ) {
        this.presenceService = presenceService;
        this.jwtDecoder = jwtDecoder;
        this.userRegistry = userRegistry;
        this.messagingTemplate = messagingTemplate;
        this.clock = clock;
    }

    /**
     * Called internally with the recipient session's original
     * CONNECT JWT.
     *
     * The listener must obtain the session/token pair from server
     * state, never from a client-supplied recipient identifier.
     *
     * Returns false when delivery is no longer authorized or the
     * session is no longer connected.
     *
     * Returns true when the message is submitted for delivery.
     * This is not an acknowledgement from the receiving device.
     */
    public boolean publishToSession(
            UUID threadId,
            String sessionId,
            Jwt sessionJwt
    ) {
        Objects.requireNonNull(
                threadId,
                "threadId is required."
        );

        if (sessionId == null
                || sessionId.isBlank()
                || sessionJwt == null) {
            return false;
        }

        UUID recipientId;

        try {
            /*
             * Your configured decoder checks the signature, expiry,
             * issuer, audience and current account authorization.
             *
             * Revalidation is necessary because a connection can
             * remain open after its token becomes invalid.
             */
            Jwt verified = jwtDecoder.decode(
                    sessionJwt.getTokenValue()
            );

            recipientId = UUID.fromString(
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
         * Confirm that this specific connection belongs to the
         * authenticated recipient.
         */
        SimpUser recipient = userRegistry.getUser(
                recipientId.toString()
        );

        if (recipient == null
                || recipient.getSession(sessionId) == null) {
            return false;
        }

        RoommatePresenceResponse presence;

        try {
            /*
             * Recheck participation, role, verification, open-thread
             * status and the confirmed chat entitlement.
             */
            presence =
                    presenceService.getOtherParticipantPresence(
                            recipientId,
                            threadId
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
             * Unexpected failures must remain visible to the caller.
             * They must not be disguised as an authorization denial.
             */
            throw exception;
        }

        RoommatePresenceEvent event =
                new RoommatePresenceEvent(
                        presence.threadId(),
                        presence.otherParticipantOnline(),
                        clock.instant()
                );

        SimpMessageHeaderAccessor headers =
                SimpMessageHeaderAccessor.create(
                        SimpMessageType.MESSAGE
                );

        headers.setSessionId(sessionId);
        headers.setLeaveMutable(true);

        /*
         * Using the session ID as the destination user, with the
         * matching session header, targets only this connection.
         */
        messagingTemplate.convertAndSendToUser(
                sessionId,
                DESTINATION,
                event,
                headers.getMessageHeaders()
        );

        return true;
    }
}