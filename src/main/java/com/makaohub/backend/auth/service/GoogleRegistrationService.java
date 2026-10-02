package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.api.AuthTokensResponse;
import com.makaohub.backend.auth.api.GoogleRegistrationRequest;
import com.makaohub.backend.auth.domain.GoogleIdentity;
import com.makaohub.backend.auth.domain.Role;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.exception.RegistrationException;
import com.makaohub.backend.auth.repository.GoogleIdentityRepository;
import com.makaohub.backend.auth.repository.RoleRepository;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoogleRegistrationService {

    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final GoogleIdentityRepository googleIdentityRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PhoneProtectionService phoneProtectionService;
    private final LegalAcceptanceService legalAcceptanceService;
    private final AuthenticationTokenService authenticationTokenService;

    public GoogleRegistrationService(
            GoogleIdTokenVerifier googleIdTokenVerifier,
            GoogleIdentityRepository googleIdentityRepository,
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            PhoneProtectionService phoneProtectionService,
            LegalAcceptanceService legalAcceptanceService,
            AuthenticationTokenService authenticationTokenService
    ) {
        this.googleIdTokenVerifier = googleIdTokenVerifier;
        this.googleIdentityRepository = googleIdentityRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.phoneProtectionService = phoneProtectionService;
        this.legalAcceptanceService = legalAcceptanceService;
        this.authenticationTokenService = authenticationTokenService;
    }

    @Transactional
    public AuthTokensResponse register(
            GoogleRegistrationRequest request
    ) {
        rejectAdminRole(request.role());

        GoogleIdTokenVerifier.VerifiedGoogleIdentity googleIdentity =
                verifyGoogleToken(request.idToken());

        if (googleIdentityRepository.existsByGoogleSubject(
                googleIdentity.subject()
        ) || userRepository.existsByEmailIgnoreCase(
                googleIdentity.email()
        )) {
            throw new RegistrationException(
                    RegistrationException.Reason.EMAIL_ALREADY_REGISTERED,
                    "This Google account is already registered. Sign in instead."
            );
        }

        String normalizedPhone =
                KenyanPhoneNumberNormalizer.normalize(
                        request.phoneNumber()
                );

        PhoneProtectionService.ProtectedPhone protectedPhone =
                phoneProtectionService.protect(normalizedPhone);

        if (userRepository.existsByPhoneLookupHash(
                protectedPhone.lookupHash()
        )) {
            throw new RegistrationException(
                    RegistrationException.Reason.PHONE_ALREADY_REGISTERED,
                    "An account already exists for this phone number."
            );
        }

        Role role = roleRepository.findByName(request.role())
                .orElseThrow(() -> new RegistrationException(
                        RegistrationException.Reason.ROLE_NOT_CONFIGURED,
                        "The selected role is not configured."
                ));

        User user = userRepository.saveAndFlush(
                new User(
                        request.fullName(),
                        googleIdentity.email(),
                        protectedPhone.ciphertext(),
                        protectedPhone.lookupHash()
                )
        );

        userRoleRepository.saveAndFlush(
                new UserRole(user, role, null)
        );

        googleIdentityRepository.save(
                new GoogleIdentity(
                        user,
                        googleIdentity.subject()
                )
        );

        legalAcceptanceService.recordCurrentDocuments(user);

        return authenticationTokenService.issue(user);
    }

    private GoogleIdTokenVerifier.VerifiedGoogleIdentity
    verifyGoogleToken(String idToken) {
        try {
            return googleIdTokenVerifier.verify(idToken);
        } catch (JwtException exception) {
            throw new AuthException(
                    AuthException.Reason.INVALID_CREDENTIALS,
                    "Google authentication failed."
            );
        }
    }

    private void rejectAdminRole(RoleName role) {
        if (role == RoleName.ADMIN) {
            throw new RegistrationException(
                    RegistrationException.Reason.ADMIN_ROLE_NOT_ALLOWED,
                    "Admin accounts cannot use public registration."
            );
        }
    }
}