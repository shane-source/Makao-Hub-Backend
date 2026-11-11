package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.api.*;
import com.makaohub.backend.auth.domain.*;
import com.makaohub.backend.auth.exception.*;
import com.makaohub.backend.auth.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GoogleRegistrationServiceTest {
    private final GoogleIdTokenVerifier verifier = mock(GoogleIdTokenVerifier.class);
    private final GoogleIdentityRepository identities = mock(GoogleIdentityRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final PhoneProtectionService phones = mock(PhoneProtectionService.class);
    private final LegalAcceptanceService legal = mock(LegalAcceptanceService.class);
    private final AuthOtpService otp = mock(AuthOtpService.class);
    private final GoogleRegistrationService service = new GoogleRegistrationService(
            verifier, identities, users, roles, userRoles, phones, legal, otp);

    private GoogleRegistrationRequest request() {
        return new GoogleRegistrationRequest("synthetic-token", null, "0712345678", RoleName.SUBLESSOR, true);
    }
    private void verifiedIdentity() {
        when(verifier.verify("synthetic-token")).thenReturn(new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
                "synthetic-subject", "synthetic@example.invalid", "Google Display Name"));
    }

    @Test void usesGoogleNameAndProtectsSuppliedPhoneWithoutGrantingApproval() {
        verifiedIdentity();
        when(phones.protect("+254712345678")).thenReturn(new PhoneProtectionService.ProtectedPhone(new byte[]{1, 2}, "synthetic-hash"));
        Role role = mock(Role.class);
        when(roles.findByName(RoleName.SUBLESSOR)).thenReturn(Optional.of(role));
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> call.getArgument(0));
        var challenge = new OtpChallengeResponse(UUID.randomUUID(), AuthOtpChallengePurpose.REGISTRATION,
                "••••@example.invalid", Instant.now().plusSeconds(300));
        when(otp.startRegistration(any())).thenReturn(challenge);
        assertSame(challenge, service.register(request()));
        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        User user = saved.getValue();
        assertEquals("Google Display Name", user.getFullName());
        assertEquals("synthetic@example.invalid", user.getEmail());
        assertArrayEquals(new byte[]{1, 2}, user.getPhoneCiphertext());
        assertEquals("synthetic-hash", user.getPhoneLookupHash());
        assertNull(user.getPasswordHash());
        verify(phones).protect("+254712345678");
        verify(users).existsByPhoneLookupHash("synthetic-hash");
        verify(userRoles).saveAndFlush(argThat(r -> r.getUser() == user && r.getRole() == role));
        verify(identities).save(argThat(i -> i.getUser() == user && i.getGoogleSubject().equals("synthetic-subject")));
        verify(legal).recordCurrentDocuments(user);
        verify(otp).startRegistration(user);
    }

    @Test void missingOrInvalidPhoneCannotReachPersistenceOrOtpEvenWithoutHttpValidation() {
        verifiedIdentity();
        for (String phone : new String[]{null, "", " ", "not-a-phone"}) {
            var failure = assertThrows(RegistrationException.class, () -> service.register(
                    new GoogleRegistrationRequest("synthetic-token", null, phone, RoleName.RENTER, true)));
            assertEquals(RegistrationException.Reason.INVALID_PHONE_NUMBER, failure.getReason());
        }
        verify(users, never()).saveAndFlush(any());
        verify(identities, never()).save(any());
        verifyNoInteractions(phones, roles, userRoles, legal, otp);
    }

    @Test void duplicatePhoneCannotCreateAnotherGoogleAccount() {
        verifiedIdentity();
        when(phones.protect("+254712345678")).thenReturn(new PhoneProtectionService.ProtectedPhone(new byte[]{1}, "synthetic-hash"));
        when(users.existsByPhoneLookupHash("synthetic-hash")).thenReturn(true);
        var failure = assertThrows(RegistrationException.class, () -> service.register(request()));
        assertEquals(RegistrationException.Reason.PHONE_ALREADY_REGISTERED, failure.getReason());
        verify(users, never()).saveAndFlush(any());
        verify(identities, never()).save(any());
        verifyNoInteractions(roles, userRoles, legal, otp);
    }

    @Test void invalidTokenCannotCreateAccount() {
        when(verifier.verify("synthetic-token")).thenThrow(new JwtException("synthetic failure"));
        assertThrows(AuthException.class, () -> service.register(request()));
        verifyNoInteractions(users, identities, roles, userRoles, phones, legal, otp);
    }

    @Test void existingEmailIsNeverAutomaticallyLinkedToAnotherGoogleSubject() {
        verifiedIdentity();
        when(users.existsByEmailIgnoreCase("synthetic@example.invalid")).thenReturn(true);
        var failure = assertThrows(RegistrationException.class, () -> service.register(request()));
        assertEquals(RegistrationException.Reason.EMAIL_ALREADY_REGISTERED, failure.getReason());
        verify(users, never()).saveAndFlush(any());
        verify(identities, never()).save(any());
        verifyNoInteractions(roles, userRoles, phones, legal, otp);
    }
}
