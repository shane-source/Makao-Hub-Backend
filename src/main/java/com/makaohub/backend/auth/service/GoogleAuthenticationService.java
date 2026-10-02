package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.api.AuthTokensResponse;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.GoogleIdentity;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.GoogleIdentityRepository;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleAuthenticationService {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final GoogleIdentityRepository googleIdentityRepository;
    private final AuthenticationTokenService authenticationTokenService;

    public GoogleAuthenticationService(
            GoogleIdTokenVerifier googleIdTokenVerifier,
            GoogleIdentityRepository googleIdentityRepository,
            AuthenticationTokenService authenticationTokenService
    ) {
        this.googleIdTokenVerifier = googleIdTokenVerifier;
        this.googleIdentityRepository = googleIdentityRepository;
        this.authenticationTokenService = authenticationTokenService;
    }

    @Transactional
    public AuthTokensResponse authenticate(String idToken) {
        GoogleIdTokenVerifier.VerifiedGoogleIdentity googleIdentity;

        try {
            googleIdentity = googleIdTokenVerifier.verify(idToken);
        } catch (JwtException exception) {
            throw new AuthException(
                    AuthException.Reason.INVALID_CREDENTIALS,
                    "Google authentication failed."
            );
        }

        GoogleIdentity storedIdentity =
                googleIdentityRepository.findByGoogleSubject(
                        googleIdentity.subject()
                ).orElseThrow(() -> new AuthException(
                        AuthException.Reason
                                .GOOGLE_REGISTRATION_REQUIRED,
                        "Complete registration before signing in."
                ));

        User user = storedIdentity.getUser();

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new AuthException(
                    AuthException.Reason.ACCOUNT_UNAVAILABLE,
                    "This account is unavailable."
            );
        }

        return authenticationTokenService.issue(user);
    }
}