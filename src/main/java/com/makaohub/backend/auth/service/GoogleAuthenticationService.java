package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.api.AuthTokensResponse;
import com.makaohub.backend.auth.api.OtpChallengeResponse;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.GoogleIdentity;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.GoogleIdentityRepository;
import com.makaohub.backend.auth.repository.UserRepository;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleAuthenticationService {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final GoogleIdentityRepository googleIdentityRepository;
    private final AuthenticationTokenService authenticationTokenService;
    private final LegacyRoleSelectionService roleSelectionService;
    private final UserRepository userRepository;
    private final AuthOtpService authOtpService;

    public GoogleAuthenticationService(
            GoogleIdTokenVerifier googleIdTokenVerifier,
            GoogleIdentityRepository googleIdentityRepository,
            AuthenticationTokenService authenticationTokenService,
            LegacyRoleSelectionService roleSelectionService,
            UserRepository userRepository,
            AuthOtpService authOtpService
    ) {
        this.googleIdTokenVerifier = googleIdTokenVerifier;
        this.googleIdentityRepository = googleIdentityRepository;
        this.authenticationTokenService = authenticationTokenService;
        this.roleSelectionService = roleSelectionService;
        this.userRepository = userRepository;
        this.authOtpService = authOtpService;
    }

    @Transactional
    public OtpChallengeResponse startLogin(String idToken, RoleName expectedRole) {
        var identity = verifyGoogleToken(idToken);
        // Email is only a candidate lookup, never sufficient proof to link or issue tokens.
        User user = googleIdentityRepository.findByGoogleSubject(identity.subject())
                .map(GoogleIdentity::getUser)
                .orElseGet(() -> userRepository.findByEmailIgnoreCaseForUpdate(identity.email())
                        .orElseThrow(() -> new AuthException(
                                AuthException.Reason.GOOGLE_REGISTRATION_REQUIRED,
                                "Complete registration before signing in.")));
        requireActiveRole(user, expectedRole);
        return authOtpService.startGoogleLogin(user, identity.subject());
    }

    @Transactional
    public AuthTokensResponse authenticate(String idToken) {
        return authenticationTokenService.issue(verifyIdentity(idToken));
    }

    @Transactional
    public User verifyIdentity(String idToken) {
        return verifyIdentity(idToken, null);
    }

    @Transactional
    public User verifyIdentity(String idToken, RoleName expectedRole) {
        var googleIdentity = verifyGoogleToken(idToken);

        GoogleIdentity storedIdentity =
                googleIdentityRepository.findByGoogleSubject(
                        googleIdentity.subject()
                ).orElseThrow(() -> new AuthException(
                        AuthException.Reason.GOOGLE_REGISTRATION_REQUIRED,
                        "Complete registration before signing in."
                ));

        User user = storedIdentity.getUser();
        requireActiveRole(user, expectedRole);
        return user;
    }

    private GoogleIdTokenVerifier.VerifiedGoogleIdentity verifyGoogleToken(String idToken) {
        GoogleIdTokenVerifier.VerifiedGoogleIdentity googleIdentity;

        try {
            googleIdentity = googleIdTokenVerifier.verify(idToken);
        } catch (JwtException exception) {
            throw new AuthException(
                    AuthException.Reason.INVALID_CREDENTIALS,
                    "Google authentication failed."
            );
        }

        return googleIdentity;
    }

    private void requireActiveRole(User user, RoleName expectedRole) {
        if (user.getAccountStatus() != AccountStatus.ACTIVE
                && user.getAccountStatus() != AccountStatus.DEACTIVATED) {
            throw new AuthException(
                    AuthException.Reason.ACCOUNT_UNAVAILABLE,
                    "This account is unavailable."
            );
        }

        // Verified Google identity first; the selected role can only constrain login, not grant a role.
        if (user.getAccountStatus() == AccountStatus.DEACTIVATED)
            roleSelectionService.resolveForReactivation(user, expectedRole);
        else roleSelectionService.resolveForLogin(user, expectedRole);
    }
}
