package com.makaohub.backend.renter.service;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.domain.RoleVerification;
import com.makaohub.backend.verification.domain.VerificationStatus;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationApplicantDetailsTest {
    @Test void returnsOnlyRealRenterVerificationAndOptionalPhoto() {
        var users = mock(UserRepository.class);
        var verifications = mock(RoleVerificationRepository.class);
        var storage = mock(SupabaseStorageClient.class);
        var user = mock(User.class);
        var verification = mock(RoleVerification.class);
        UUID renter = UUID.randomUUID();
        when(users.findById(renter)).thenReturn(Optional.of(user));
        when(user.getFullName()).thenReturn("Example Renter");
        when(user.getAvatarObjectPath()).thenReturn("private-avatar");
        when(storage.createSignedAvatarReadUrl("private-avatar")).thenReturn("signed-photo");
        when(verifications.findByUserIdAndRole(renter, RoleName.RENTER)).thenReturn(Optional.of(verification));
        when(verification.getStatus()).thenReturn(VerificationStatus.VERIFIED);
        var result = new ApplicationApplicantDetails(users, verifications, storage).forApplicant(renter);
        assertEquals("Example Renter", result.name);
        assertEquals("signed-photo", result.avatarUrl);
        assertEquals("VERIFIED", result.verificationStatus);
        assertFalse(result.toString().contains("signed-photo"));
    }

    @Test void missingRenterOrVerificationNeverProducesVerifiedBadge() {
        var users = mock(UserRepository.class);
        var verifications = mock(RoleVerificationRepository.class);
        var storage = mock(SupabaseStorageClient.class);
        var user = mock(User.class);
        UUID renter = UUID.randomUUID();
        when(users.findById(renter)).thenReturn(Optional.of(user));
        when(verifications.findByUserIdAndRole(renter, RoleName.RENTER)).thenReturn(Optional.empty());
        var result = new ApplicationApplicantDetails(users, verifications, storage).forApplicant(renter);
        assertNull(result.verificationStatus);
        assertNull(result.avatarUrl);
        verifyNoInteractions(storage);
    }
}
