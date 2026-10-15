package com.makaohub.backend.messaging.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.messaging.domain.ChatThreadStatus;
import com.makaohub.backend.messaging.domain.RentalChatThread;
import com.makaohub.backend.messaging.repository.RentalChatThreadRepository;
import com.makaohub.backend.verification.domain.RoleVerification;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RentalPresenceServiceTest {

    private UserRepository userRepository;
    private UserRoleRepository roleRepository;
    private RoleVerificationRepository verificationRepository;
    private RentalChatThreadRepository threadRepository;
    private RoommatePresenceRegistry presenceRegistry;
    private RentalPresenceService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        roleRepository = mock(UserRoleRepository.class);
        verificationRepository =
                mock(RoleVerificationRepository.class);
        threadRepository =
                mock(RentalChatThreadRepository.class);
        presenceRegistry =
                mock(RoommatePresenceRegistry.class);

        service = new RentalPresenceService(
                userRepository,
                roleRepository,
                verificationRepository,
                threadRepository,
                presenceRegistry
        );
    }

    @Test
    void verifiedRenterCanSeeEligibleLandlordPresence() {
        UUID renterId = UUID.randomUUID();
        UUID landlordId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();

        RentalChatThread thread = thread(
                threadId,
                renterId,
                landlordId,
                ChatThreadStatus.OPEN
        );

        activeAccount(renterId);
        activeAccount(landlordId);
        verifiedRole(renterId, RoleName.RENTER);
        verifiedRole(landlordId, RoleName.LANDLORD);

        when(threadRepository.findById(threadId))
                .thenReturn(Optional.of(thread));
        when(presenceRegistry.isOnline(landlordId))
                .thenReturn(true);

        var response = service.getOtherParticipantPresence(
                renterId,
                threadId
        );

        assertEquals(threadId, response.threadId());
        assertTrue(response.otherParticipantOnline());
    }

    @Test
    void outsiderCannotProbeConversationPresence() {
        UUID outsiderId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();

        RentalChatThread thread = thread(
                threadId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                ChatThreadStatus.OPEN
        );

        activeAccount(outsiderId);

        when(threadRepository.findById(threadId))
                .thenReturn(Optional.of(thread));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.getOtherParticipantPresence(
                        outsiderId,
                        threadId
                )
        );

        assertEquals(404, exception.getStatusCode().value());
        verify(presenceRegistry, never()).isOnline(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void renterWithoutCurrentRenterRoleCannotSeePresence() {
        UUID renterId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();

        RentalChatThread thread = thread(
                threadId,
                renterId,
                UUID.randomUUID(),
                ChatThreadStatus.OPEN
        );

        activeAccount(renterId);

        when(threadRepository.findById(threadId))
                .thenReturn(Optional.of(thread));

        when(roleRepository.existsByUser_IdAndRole_Name(
                renterId,
                RoleName.RENTER
        )).thenReturn(false);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.getOtherParticipantPresence(
                        renterId,
                        threadId
                )
        );

        assertEquals(404, exception.getStatusCode().value());
        verify(presenceRegistry, never()).isOnline(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void closedConversationDoesNotExposePresence() {
        UUID renterId = UUID.randomUUID();
        UUID landlordId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();

        RentalChatThread thread = thread(
                threadId,
                renterId,
                landlordId,
                ChatThreadStatus.CLOSED
        );

        activeAccount(renterId);
        verifiedRole(renterId, RoleName.RENTER);

        when(threadRepository.findById(threadId))
                .thenReturn(Optional.of(thread));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.getOtherParticipantPresence(
                        renterId,
                        threadId
                )
        );

        assertEquals(409, exception.getStatusCode().value());
        verify(presenceRegistry, never()).isOnline(landlordId);
    }

    private void activeAccount(UUID userId) {
        User user = mock(User.class);

        when(user.getAccountStatus())
                .thenReturn(AccountStatus.ACTIVE);
        when(user.isRoleSelectionRequired())
                .thenReturn(false);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user));
    }

    private void verifiedRole(
            UUID userId,
            RoleName role
    ) {
        RoleVerification verification =
                mock(RoleVerification.class);

        when(roleRepository.existsByUser_IdAndRole_Name(
                userId,
                role
        )).thenReturn(true);

        when(verification.getStatus())
                .thenReturn(VerificationStatus.VERIFIED);

        when(verificationRepository.findByUserIdAndRole(
                userId,
                role
        )).thenReturn(Optional.of(verification));
    }

    private static RentalChatThread thread(
            UUID threadId,
            UUID renterId,
            UUID landlordId,
            ChatThreadStatus status
    ) {
        RentalChatThread thread = mock(RentalChatThread.class);

        when(thread.getId()).thenReturn(threadId);
        when(thread.getRenterId()).thenReturn(renterId);
        when(thread.getLandlordId()).thenReturn(landlordId);
        when(thread.getStatus()).thenReturn(status);

        return thread;
    }
}
