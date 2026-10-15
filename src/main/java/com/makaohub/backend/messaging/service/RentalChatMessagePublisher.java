package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.api.RentalChatMessageResponse;
import com.makaohub.backend.messaging.api.RentalChatThreadResponse;
import com.makaohub.backend.messaging.domain.ChatThreadStatus;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class RentalChatMessagePublisher {

    private static final String DESTINATION =
            "/queue/rental-chat.messages";

    private final RoommatePresenceRegistry sessionRegistry;
    private final RentalChatThreadService threadService;
    private final JwtDecoder jwtDecoder;
    private final SimpUserRegistry userRegistry;
    private final SimpMessagingTemplate messagingTemplate;

    public RentalChatMessagePublisher(
            RoommatePresenceRegistry sessionRegistry,
            RentalChatThreadService threadService,
            JwtDecoder jwtDecoder,
            SimpUserRegistry userRegistry,
            SimpMessagingTemplate messagingTemplate
    ) {
        this.sessionRegistry = sessionRegistry;
        this.threadService = threadService;
        this.jwtDecoder = jwtDecoder;
        this.userRegistry = userRegistry;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Called by the controller after messageService.send() returns
     * through its transaction proxy and the transaction has committed.
     *
     * Returns broker submissions, not client delivery acknowledgements.
     */
    public int publish(RentalChatMessageService.SentMessage sent) {
        Objects.requireNonNull(sent, "Saved message result is required.");

        RentalChatMessageResponse message = Objects.requireNonNull(
                sent.message(),
                "Saved message is required."
        );

        /*
         * Fail rather than publishing while a database transaction
         * could still roll back.
         */
        if (TransactionSynchronizationManager
                .isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Publish chat messages after the transaction completes."
            );
        }

        UUID renterId = Objects.requireNonNull(
                sent.renterId(),
                "Renter ID is required."
        );

        UUID landlordId = Objects.requireNonNull(
                sent.landlordId(),
                "Landlord ID is required."
        );

        if (renterId.equals(landlordId)
                || (!renterId.equals(message.senderId())
                && !landlordId.equals(message.senderId()))) {
            throw new IllegalArgumentException(
                    "Saved message participants are invalid."
            );
        }

        /*
         * A retry acknowledges the stored message to its sender.
         * It does not rebroadcast the same message to the other party.
         */
        if (!sent.newlyCreated()) {
            RoleName senderRole = renterId.equals(message.senderId())
                    ? RoleName.RENTER
                    : RoleName.LANDLORD;

            return publishToParticipant(
                    message.senderId(),
                    senderRole,
                    message
            );
        }

        int submitted = publishToParticipant(
                renterId,
                RoleName.RENTER,
                message
        );

        submitted += publishToParticipant(
                landlordId,
                RoleName.LANDLORD,
                message
        );

        return submitted;
    }

    /**
     * Attempts delivery to each recorded connection for one participant.
     */
    private int publishToParticipant(
            UUID recipientId,
            RoleName requiredRole,
            RentalChatMessageResponse message
    ) {
        int submitted = 0;

        for (RoommatePresenceRegistry.AuthenticatedSession session
                : sessionRegistry.sessionsFor(recipientId)) {
            if (publishToSession(
                    recipientId,
                    requiredRole,
                    message,
                    session
            )) {
                submitted++;
            }
        }

        return submitted;
    }

    /**
     * Revalidates the session and conversation access before delivery.
     */
    private boolean publishToSession(
            UUID recipientId,
            RoleName requiredRole,
            RentalChatMessageResponse message,
            RoommatePresenceRegistry.AuthenticatedSession session
    ) {
        if (!hasValidCredential(
                recipientId,
                requiredRole,
                session
        )) {
            return false;
        }

        SimpUser user = userRegistry.getUser(recipientId.toString());

        if (user == null
                || user.getSession(session.sessionId()) == null) {
            return false;
        }

        if (!canReceive(
                recipientId,
                requiredRole,
                message.threadId()
        )) {
            return false;
        }

        SimpMessageHeaderAccessor headers =
                SimpMessageHeaderAccessor.create(
                        SimpMessageType.MESSAGE
                );

        headers.setSessionId(session.sessionId());
        headers.setLeaveMutable(true);

        /*
         * Target the exact session just authorized.
         * Other sessions belonging to the account are checked separately.
         */
        messagingTemplate.convertAndSendToUser(
                session.sessionId(),
                DESTINATION,
                message,
                headers.getMessageHeaders()
        );

        return true;
    }

    /**
     * Revalidates the stored access token using the configured decoder.
     *
     * The token must identify this recipient and carry exactly the
     * role required for this side of the conversation.
     */
    private boolean hasValidCredential(
            UUID recipientId,
            RoleName requiredRole,
            RoommatePresenceRegistry.AuthenticatedSession session
    ) {
        try {
            Jwt verified = jwtDecoder.decode(
                    session.jwt().getTokenValue()
            );

            UUID tokenUserId = UUID.fromString(
                    verified.getSubject()
            );

            List<String> roles =
                    verified.getClaimAsStringList("roles");

            return recipientId.equals(tokenUserId)
                    && "access".equals(
                    verified.getClaimAsString("token_type")
            )
                    && List.of(requiredRole.name()).equals(roles);

        } catch (JwtException
                 | IllegalArgumentException
                 | NullPointerException exception) {
            return false;
        }
    }

    /**
     * Reuses account, role, verification and ownership checks.
     *
     * Closed conversations remain available through authorized history
     * requests, but do not receive live message delivery here.
     */
    private boolean canReceive(
            UUID recipientId,
            RoleName requiredRole,
            UUID threadId
    ) {
        try {
            RentalChatThreadResponse thread;

            if (requiredRole == RoleName.RENTER) {
                thread = threadService.findForRenter(
                        recipientId,
                        threadId
                );
            } else if (requiredRole == RoleName.LANDLORD) {
                thread = threadService.findForLandlord(
                        recipientId,
                        threadId
                );
            } else {
                return false;
            }

            return thread.status() == ChatThreadStatus.OPEN;

        } catch (AuthException exception) {
            return false;
        } catch (ResponseStatusException exception) {
            int status = exception.getStatusCode().value();

            if (status == 401
                    || status == 403
                    || status == 404) {
                return false;
            }

            throw exception;
        }
    }
}