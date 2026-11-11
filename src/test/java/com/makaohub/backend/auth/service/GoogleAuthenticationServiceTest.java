package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.GoogleIdentity;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.GoogleIdentityRepository;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.domain.RoleName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class GoogleAuthenticationServiceTest {

    private final GoogleIdTokenVerifier verifier =
            mock(GoogleIdTokenVerifier.class);

    private final GoogleIdentityRepository identities =
            mock(GoogleIdentityRepository.class);

    private final AuthenticationTokenService tokens =
            mock(AuthenticationTokenService.class);

    private final LegacyRoleSelectionService selection =
            mock(LegacyRoleSelectionService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AuthOtpService otp = mock(AuthOtpService.class);

    private final GoogleAuthenticationService service =
            new GoogleAuthenticationService(
                    verifier,
                    identities,
                    tokens,
                    selection, users, otp
            );

    @Test
    void invalidGoogleTokenCannotAuthenticate() {
        when(verifier.verify("invalid"))
                .thenThrow(new JwtException("invalid"));

        assertThrows(
                AuthException.class,
                () -> service.authenticate("invalid")
        );

        verifyNoInteractions(
                identities,
                selection,
                tokens
        );
    }

    @Test
    void storedGoogleAccountUsesServerSideRole() {
        User user = mock(User.class);

        when(user.getAccountStatus())
                .thenReturn(AccountStatus.ACTIVE);

        when(verifier.verify("valid"))
                .thenReturn(
                        new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
                                "subject-123",
                                "verified@example.invalid",
                                "Verified User"
                        )
                );

        when(identities.findByGoogleSubject("subject-123"))
                .thenReturn(
                        Optional.of(
                                new GoogleIdentity(
                                        user,
                                        "subject-123"
                                )
                        )
                );

        service.authenticate("valid");

        var order = inOrder(selection, tokens);

        order.verify(selection)
                .resolveForLogin(user, null);

        order.verify(tokens)
                .issue(user);
    }

    @Test
    void passwordAccountWithMatchingVerifiedEmailStartsOtpWithoutCreatingAnIdentityOrSession() {
        User user = mock(User.class);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(verifier.verify("valid")).thenReturn(new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
                "subject-123", "existing@example.invalid", "Google Name"));
        when(users.findByEmailIgnoreCaseForUpdate("existing@example.invalid")).thenReturn(Optional.of(user));

        service.startLogin("valid", RoleName.RENTER);

        var order = inOrder(selection, otp);
        order.verify(selection).resolveForLogin(user, RoleName.RENTER);
        order.verify(otp).startGoogleLogin(user, "subject-123");
        verify(identities, never()).save(any());
        verifyNoInteractions(tokens);
    }

    @Test
    void invalidTokenUnknownEmailSuspendedAccountAndWrongRoleCannotStartOtp() {
        when(verifier.verify("invalid")).thenThrow(new JwtException("invalid"));
        assertThrows(AuthException.class, () -> service.startLogin("invalid", RoleName.RENTER));
        verifyNoInteractions(users, identities);
        when(verifier.verify("valid")).thenReturn(new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
                "subject-123", "existing@example.invalid", "Google Name"));
        assertEquals(AuthException.Reason.GOOGLE_REGISTRATION_REQUIRED,
                assertThrows(AuthException.class, () -> service.startLogin("valid", RoleName.RENTER)).getReason());
        User user = mock(User.class);
        when(users.findByEmailIgnoreCaseForUpdate("existing@example.invalid")).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.SUSPENDED);
        assertThrows(AuthException.class, () -> service.startLogin("valid", RoleName.RENTER));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        doThrow(new AuthException(AuthException.Reason.ROLE_MISMATCH, "Wrong role"))
                .when(selection).resolveForLogin(user, RoleName.RENTER);
        assertThrows(AuthException.class, () -> service.startLogin("valid", RoleName.RENTER));
        verifyNoInteractions(otp, tokens);
    }

    @Test
    void linkedSubjectWinsOverEmailAndDirectAuthenticationCannotUseEmailFallback() {
        User user = mock(User.class);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(verifier.verify("valid")).thenReturn(new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
                "subject-123", "changed@example.invalid", "Google Name"));
        when(identities.findByGoogleSubject("subject-123"))
                .thenReturn(Optional.of(new GoogleIdentity(user, "subject-123")));
        service.startLogin("valid", RoleName.LANDLORD);
        verify(otp).startGoogleLogin(user, "subject-123");
        verifyNoInteractions(users, tokens);
        when(identities.findByGoogleSubject("subject-123")).thenReturn(Optional.empty());
        assertThrows(AuthException.class, () -> service.authenticate("valid"));
        verifyNoInteractions(users, tokens);
    }
}
