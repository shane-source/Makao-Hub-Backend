package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.messaging.api.RentalPresenceResponse;
import com.makaohub.backend.messaging.domain.ChatThreadStatus;
import com.makaohub.backend.messaging.domain.RentalChatThread;
import com.makaohub.backend.messaging.repository.RentalChatThreadRepository;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class RentalPresenceService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleVerificationRepository verificationRepository;
    private final RentalChatThreadRepository threadRepository;
    private final RoommatePresenceRegistry presenceRegistry;

    public RentalPresenceService(
            UserRepository userRepository,
            UserRoleRepository userRoleRepository,
            RoleVerificationRepository verificationRepository,
            RentalChatThreadRepository threadRepository,
            RoommatePresenceRegistry presenceRegistry
    ) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.verificationRepository = verificationRepository;
        this.threadRepository = threadRepository;
        this.presenceRegistry = presenceRegistry;
    }

    /**
     * Returns the current online status of the other participant in
     * an authorized Renter-Landlord conversation.
     *
     * The authenticated user ID must come from the verified JWT.
     * The client never supplies the other participant's user ID.
     */
    @Transactional(readOnly = true)
    public RentalPresenceResponse getOtherParticipantPresence(
            UUID authenticatedUserId,
            UUID threadId
    ) {
        requireActiveAccount(authenticatedUserId);

        if (threadId == null) {
            throw threadNotFound();
        }

        RentalChatThread thread = threadRepository
                .findById(threadId)
                .orElseThrow(
                        RentalPresenceService::threadNotFound
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
         * The other participant is shown as online only when:
         *
         * 1. their account remains active;
         * 2. role selection is complete;
         * 3. they still hold the role required by this thread;
         * 4. that role remains verified;
         * 5. at least one valid WebSocket session exists.
         *
         * A failed eligibility check is represented as offline.
         * Its precise reason is not disclosed to the requester.
         */
        boolean counterpartEligible =
                isEligibleParticipant(
                        participant.otherParticipantId(),
                        participant.otherParticipantRole()
                );

        boolean online =
                counterpartEligible
                        && presenceRegistry.isOnline(
                        participant.otherParticipantId()
                );

        return new RentalPresenceResponse(
                thread.getId(),
                online
        );
    }

    /**
     * Confirms that the authenticated account belongs to the thread.
     *
     * A nonparticipant receives 404 instead of 403 so the endpoint
     * does not reveal whether another user's conversation exists.
     */
    private ParticipantContext requireParticipant(
            UUID authenticatedUserId,
            RentalChatThread thread
    ) {
        if (thread.getRenterId().equals(authenticatedUserId)) {
            requireRole(
                    authenticatedUserId,
                    RoleName.RENTER
            );

            return new ParticipantContext(
                    RoleName.RENTER,
                    thread.getLandlordId(),
                    RoleName.LANDLORD
            );
        }

        if (thread.getLandlordId().equals(authenticatedUserId)) {
            requireRole(
                    authenticatedUserId,
                    RoleName.LANDLORD
            );

            return new ParticipantContext(
                    RoleName.LANDLORD,
                    thread.getRenterId(),
                    RoleName.RENTER
            );
        }

        throw threadNotFound();
    }

    /**
     * Requires the role currently stored by the backend.
     *
     * No role supplied by Android or the website is trusted.
     */
    private void requireRole(
            UUID userId,
            RoleName requiredRole
    ) {
        if (!hasRole(userId, requiredRole)) {
            throw threadNotFound();
        }
    }

    private boolean hasRole(
            UUID userId,
            RoleName requiredRole
    ) {
        return userRoleRepository
                .existsByUser_IdAndRole_Name(
                        userId,
                        requiredRole
                );
    }

    /**
     * Verification belongs to the specific active role.
     *
     * Renter verification cannot satisfy Landlord verification, and
     * Landlord verification cannot satisfy Renter verification.
     */
    private void requireVerifiedRole(
            UUID userId,
            RoleName requiredRole
    ) {
        if (!isVerifiedRole(userId, requiredRole)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "A verified role is required for chat presence."
            );
        }
    }

    private boolean isVerifiedRole(
            UUID userId,
            RoleName requiredRole
    ) {
        return verificationRepository
                .findByUserIdAndRole(
                        userId,
                        requiredRole
                )
                .map(verification ->
                        verification.getStatus()
                                == VerificationStatus.VERIFIED
                )
                .orElse(false);
    }

    /**
     * Checks the other participant without exposing why they are
     * unavailable.
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
     * Presence is available only for an open conversation.
     *
     * No payment or chat entitlement is required because Makao Hub
     * rental chat is currently unlocked.
     */
    private static void requireOpenThread(
            RentalChatThread thread
    ) {
        if (thread.getStatus() != ChatThreadStatus.OPEN) {
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
            UUID authenticatedUserId
    ) {
        if (authenticatedUserId == null) {
            throw accountUnavailable();
        }

        User user = userRepository
                .findById(authenticatedUserId)
                .orElseThrow(
                        RentalPresenceService::accountUnavailable
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
     * Checks the other participant without revealing whether their
     * account is missing, restricted or awaiting role selection.
     */
    private boolean isActiveAccount(UUID userId) {
        return userRepository
                .findById(userId)
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