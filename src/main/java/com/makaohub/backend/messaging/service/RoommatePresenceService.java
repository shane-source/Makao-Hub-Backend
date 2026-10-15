package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.messaging.api.RoommatePresenceResponse;
import com.makaohub.backend.messaging.domain.RoommateChatThread;
import com.makaohub.backend.messaging.domain.RoommateChatThreadStatus;
import com.makaohub.backend.messaging.repository.RoommateChatThreadRepository;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class RoommatePresenceService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleVerificationRepository verificationRepository;
    private final RoommateChatThreadRepository threadRepository;
    private final RoommatePresenceRegistry presenceRegistry;

    public RoommatePresenceService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleVerificationRepository verificationRepository,
            RoommateChatThreadRepository threadRepository,
            RoommatePresenceRegistry presenceRegistry
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.verificationRepository = verificationRepository;
        this.threadRepository = threadRepository;
        this.presenceRegistry = presenceRegistry;
    }

    /**
     * Returns the current online state of the other participant in a
     * private roommate chat.
     *
     * Presence is returned only after the caller, role and thread have
     * been authorized by the backend.
     */
    @Transactional(readOnly = true)
    public RoommatePresenceResponse getOtherParticipantPresence(
            UUID authenticatedUserId,
            UUID threadId
    ) {
        requireActiveAccount(authenticatedUserId);

        RoommateChatThread thread =
                threadRepository.findById(threadId)
                        .orElseThrow(
                                RoommatePresenceService
                                        ::threadNotFound
                        );

        ParticipantContext participant =
                requireParticipant(
                        authenticatedUserId,
                        thread
                );

        requireVerifiedRole(
                authenticatedUserId,
                participant.authenticatedRole()
        );

        requireOpenThread(thread);

        /*
         * The other participant is considered available only when:
         *
         * 1. their account is active;
         * 2. role selection has been completed;
         * 3. they still hold the role required by this thread;
         * 4. that specific role remains verified.
         *
         * This prevents an old WebSocket connection from exposing
         * presence after a role switch or account restriction.
         */
        boolean counterpartAvailable =
                isEligibleParticipant(
                        participant.otherParticipantId(),
                        participant.otherParticipantRole()
                );

        boolean online =
                counterpartAvailable
                        && presenceRegistry.isOnline(
                        participant.otherParticipantId()
                );

        return new RoommatePresenceResponse(
                thread.getId(),
                online
        );
    }

    /**
     * Confirms that the caller belongs to the requested thread and
     * determines which participant is on the other side.
     *
     * A nonparticipant receives 404 instead of 403. This avoids
     * revealing that a private conversation exists.
     */
    private ParticipantContext requireParticipant(
            UUID authenticatedUserId,
            RoommateChatThread thread
    ) {
        if (thread.getRenterId().equals(authenticatedUserId)) {
            requireRole(
                    authenticatedUserId,
                    RoleName.RENTER
            );

            return new ParticipantContext(
                    RoleName.RENTER,
                    thread.getSublessorId(),
                    RoleName.SUBLESSOR
            );
        }

        if (thread.getSublessorId().equals(authenticatedUserId)) {
            requireRole(
                    authenticatedUserId,
                    RoleName.SUBLESSOR
            );

            return new ParticipantContext(
                    RoleName.SUBLESSOR,
                    thread.getRenterId(),
                    RoleName.RENTER
            );
        }

        throw threadNotFound();
    }

    /**
     * Requires the role currently stored by the backend.
     *
     * No role supplied by the client is trusted.
     */
    private void requireRole(
            UUID userId,
            RoleName role
    ) {
        if (!hasRole(userId, role)) {
            throw threadNotFound();
        }
    }

    /**
     * Checks whether the user currently has the requested role.
     */
    private boolean hasRole(
            UUID userId,
            RoleName role
    ) {
        return userRoleRepository
                .existsByUser_IdAndRole_Name(
                        userId,
                        role
                );
    }

    /**
     * All public Makao Hub roles require independent verification.
     *
     * Verification from another role is never inherited.
     */
    private void requireVerifiedRole(
            UUID userId,
            RoleName role
    ) {
        if (!isVerifiedRole(userId, role)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Verified role is required for chat presence."
            );
        }
    }

    /**
     * Checks one role-specific verification record.
     */
    private boolean isVerifiedRole(
            UUID userId,
            RoleName role
    ) {
        return verificationRepository
                .findByUserIdAndRole(userId, role)
                .map(verification ->
                        verification.getStatus()
                                == VerificationStatus.VERIFIED
                )
                .orElse(false);
    }

    /**
     * Checks whether another participant may safely appear in a
     * presence result.
     *
     * All checks must pass. Failure is represented as offline so
     * private account or verification information is not revealed.
     */
    private boolean isEligibleParticipant(
            UUID userId,
            RoleName requiredRole
    ) {
        return isActiveAccount(userId)
                && hasRole(userId, requiredRole)
                && isVerifiedRole(userId, requiredRole);
    }

    /**
     * Presence is available only while the conversation remains open.
     *
     * Chat presence does not require a paid entitlement.
     */
    private void requireOpenThread(
            RoommateChatThread thread
    ) {
        if (thread.getStatus()
                != RoommateChatThreadStatus.OPEN) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Chat thread is closed."
            );
        }
    }

    /**
     * Requires the requesting account to be active and to have
     * completed role selection.
     */
    private void requireActiveAccount(
            UUID userId
    ) {
        User user = userRepository.findById(userId)
                .orElseThrow(
                        RoommatePresenceService
                                ::accountUnavailable
                );

        if (user.getAccountStatus() != AccountStatus.ACTIVE
                || user.isRoleSelectionRequired()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account is not active."
            );
        }
    }

    /**
     * Checks another participant without revealing why they are
     * unavailable.
     */
    private boolean isActiveAccount(
            UUID userId
    ) {
        return userRepository.findById(userId)
                .map(user ->
                        user.getAccountStatus()
                                == AccountStatus.ACTIVE
                                && !user.isRoleSelectionRequired()
                )
                .orElse(false);
    }

    private static ResponseStatusException threadNotFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Chat thread not found."
        );
    }

    private static AuthException accountUnavailable() {
        return new AuthException(
                AuthException.Reason.ACCOUNT_UNAVAILABLE,
                "The account is unavailable."
        );
    }

    /**
     * Internal participant mapping.
     *
     * This record is never returned to Android or the website.
     */
    private record ParticipantContext(
            RoleName authenticatedRole,
            UUID otherParticipantId,
            RoleName otherParticipantRole
    ) {
    }
}