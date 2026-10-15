package com.makaohub.backend.verification.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.verification.domain.RoleVerification;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoleVerificationGuardTest {
    private final RoleVerificationRepository repository = mock(RoleVerificationRepository.class);
    private final RoleVerificationGuard guard = new RoleVerificationGuard(repository);

    @Test
    void rejectsMissingVerification() {
        UUID renterId = UUID.randomUUID();
        when(repository.findByUserIdAndRole(renterId, RoleName.RENTER))
                .thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> guard.requireVerified(renterId, RoleName.RENTER));
    }

    @Test
    void rejectsPendingVerification() {
        UUID renterId = UUID.randomUUID();
        RoleVerification verification = mock(RoleVerification.class);
        when(repository.findByUserIdAndRole(renterId, RoleName.RENTER))
                .thenReturn(Optional.of(verification));
        when(verification.getStatus()).thenReturn(VerificationStatus.PENDING);
        assertThrows(ResponseStatusException.class,
                () -> guard.requireVerified(renterId, RoleName.RENTER));
    }
}
