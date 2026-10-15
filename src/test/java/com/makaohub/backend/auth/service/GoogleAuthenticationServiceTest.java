package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.GoogleIdentity;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.GoogleIdentityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoogleAuthenticationServiceTest {

    private final GoogleIdTokenVerifier verifier =
            mock(GoogleIdTokenVerifier.class);

    private final GoogleIdentityRepository identities =
            mock(GoogleIdentityRepository.class);

    private final AuthenticationTokenService tokens =
            mock(AuthenticationTokenService.class);

    private final LegacyRoleSelectionService selection =
            mock(LegacyRoleSelectionService.class);

    private final GoogleAuthenticationService service =
            new GoogleAuthenticationService(
                    verifier,
                    identities,
                    tokens,
                    selection
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
                .resolve(user, null);

        order.verify(tokens)
                .issue(user);
    }
}