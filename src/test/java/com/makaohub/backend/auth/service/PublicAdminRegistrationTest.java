package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.api.GoogleRegistrationRequest;
import com.makaohub.backend.auth.api.RegisterRequest;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.exception.RegistrationException;
import com.makaohub.backend.auth.repository.GoogleIdentityRepository;
import com.makaohub.backend.auth.repository.RoleRepository;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PublicAdminRegistrationTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private GoogleIdentityRepository googleIdentityRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PhoneProtectionService phoneProtectionService;
    @Mock private LegalAcceptanceService legalAcceptanceService;
    @Mock private GoogleIdTokenVerifier googleIdTokenVerifier;
    @Mock private AuthenticationTokenService authenticationTokenService;
    @Mock private AuthOtpService authOtpService;

    @InjectMocks private RegistrationService registrationService;
    @InjectMocks private GoogleRegistrationService googleRegistrationService;

    @Test
    void passwordRegistrationRejectsAdminBeforeAnySideEffects() {
        RegisterRequest request = new RegisterRequest(
                "Test Applicant",
                "applicant@example.invalid",
                "+254712345678",
                "TestOnlyPassword123!",
                RoleName.ADMIN,
                true
        );

        RegistrationException exception = assertThrows(
                RegistrationException.class,
                () -> registrationService.register(request)
        );

        assertEquals(
                RegistrationException.Reason.ADMIN_ROLE_NOT_ALLOWED,
                exception.getReason()
        );
        verifyNoInteractions(
                userRepository, roleRepository, userRoleRepository,
                passwordEncoder, phoneProtectionService, legalAcceptanceService
        );
    }

    @Test
    void googleRegistrationRejectsAdminBeforeTokenVerificationOrSideEffects() {
        GoogleRegistrationRequest request = new GoogleRegistrationRequest(
                "unused-test-token",
                "Test Applicant",
                "+254712345678",
                RoleName.ADMIN,
                true
        );

        RegistrationException exception = assertThrows(
                RegistrationException.class,
                () -> googleRegistrationService.register(request)
        );

        assertEquals(
                RegistrationException.Reason.ADMIN_ROLE_NOT_ALLOWED,
                exception.getReason()
        );
        verifyNoInteractions(
                googleIdTokenVerifier, googleIdentityRepository,
                userRepository, roleRepository, userRoleRepository,
                phoneProtectionService, legalAcceptanceService,
                authOtpService
        );
    }
}
