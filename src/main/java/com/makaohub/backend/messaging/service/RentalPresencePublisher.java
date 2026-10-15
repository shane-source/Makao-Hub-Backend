package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.api.RentalPresenceEvent;
import com.makaohub.backend.messaging.api.RentalPresenceResponse;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RentalPresencePublisher {

    private static final String DESTINATION =
            "/queue/rental-chat.presence";

    private final RentalPresenceService presenceService;
    private final JwtDecoder jwtDecoder;
    private final SimpUserRegistry userRegistry;
    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;

    public RentalPresencePublisher(
            RentalPresenceService presenceService,
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
     * Sends a presence event only to the selected participant session.
     * The session and its original JWT come from server-side presence
     * state; they are never accepted from a WebSocket client.
     */
    public boolean publishToSession(
            UUID recipientId,
            RoleName requiredRole,
            UUID threadId,
            RoommatePresenceRegistry.AuthenticatedSession session
    ) {
        Objects.requireNonNull(recipientId, "recipientId is required.");
        Objects.requireNonNull(requiredRole, "requiredRole is required.");
        Objects.requireNonNull(threadId, "threadId is required.");

        if (!hasValidCredential(recipientId, requiredRole, session)) {
            return false;
        }

        SimpUser recipient = userRegistry.getUser(recipientId.toString());

        if (recipient == null
                || recipient.getSession(session.sessionId()) == null) {
            return false;
        }

        RentalPresenceResponse presence;

        try {
            presence = presenceService.getOtherParticipantPresence(
                    recipientId,
                    threadId
            );
        } catch (AuthException exception) {
            return false;
        } catch (ResponseStatusException exception) {
            int status = exception.getStatusCode().value();

            if (status == 401
                    || status == 403
                    || status == 404
                    || status == 409) {
                return false;
            }

            throw exception;
        }

        RentalPresenceEvent event = new RentalPresenceEvent(
                presence.threadId(),
                presence.otherParticipantOnline(),
                clock.instant()
        );

        SimpMessageHeaderAccessor headers =
                SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(session.sessionId());
        headers.setLeaveMutable(true);

        messagingTemplate.convertAndSendToUser(
                session.sessionId(),
                DESTINATION,
                event,
                headers.getMessageHeaders()
        );

        return true;
    }

    private boolean hasValidCredential(
            UUID recipientId,
            RoleName requiredRole,
            RoommatePresenceRegistry.AuthenticatedSession session
    ) {
        if (session == null
                || session.sessionId() == null
                || session.sessionId().isBlank()
                || session.jwt() == null) {
            return false;
        }

        try {
            Jwt verified = jwtDecoder.decode(
                    session.jwt().getTokenValue()
            );

            UUID tokenUserId = UUID.fromString(verified.getSubject());
            List<String> roles = verified.getClaimAsStringList("roles");

            return recipientId.equals(tokenUserId)
                    && "access".equals(
                    verified.getClaimAsString("token_type")
            )
                    && List.of(requiredRole.name()).equals(roles);
        } catch (
                JwtException
                | IllegalArgumentException
                | NullPointerException exception
        ) {
            return false;
        }
    }
}
