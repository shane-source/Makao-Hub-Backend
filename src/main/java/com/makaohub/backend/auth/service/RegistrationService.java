package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.api.RegisterRequest;
import com.makaohub.backend.auth.api.OtpChallengeResponse;
import com.makaohub.backend.auth.domain.Role;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.domain.UserRole;
import com.makaohub.backend.auth.exception.RegistrationException;
import com.makaohub.backend.auth.repository.RoleRepository;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class RegistrationService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneProtectionService phoneProtectionService;
    private final LegalAcceptanceService legalAcceptanceService;
    private final AuthOtpService authOtpService;

    public RegistrationService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            UserRoleRepository userRoleRepository,
            PasswordEncoder passwordEncoder,
            PhoneProtectionService phoneProtectionService,
            LegalAcceptanceService legalAcceptanceService,
            AuthOtpService authOtpService
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.phoneProtectionService = phoneProtectionService;
        this.legalAcceptanceService = legalAcceptanceService;
        this.authOtpService = authOtpService;
    }

    @Transactional
    public OtpChallengeResponse register(RegisterRequest request) {
        rejectAdminRole(request.role());
        enforcePasswordLimits(request.password());

        String normalizedEmail = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new RegistrationException(
                    RegistrationException.Reason.EMAIL_ALREADY_REGISTERED,
                    "An account already exists for this email."
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

        String passwordHash =
                passwordEncoder.encode(request.password());

        User user = userRepository.saveAndFlush(
                new User(
                        request.fullName(),
                        normalizedEmail,
                        passwordHash,
                        protectedPhone.ciphertext(),
                        protectedPhone.lookupHash()
                )
        );

        userRoleRepository.saveAndFlush(
                new UserRole(user, role, null)
        );

        legalAcceptanceService.recordCurrentDocuments(user);

        // Do not create an authenticated session until the account email OTP succeeds.
        return authOtpService.startRegistration(user);
    }

    private void rejectAdminRole(RoleName role) {
        if (role == RoleName.ADMIN) {
            throw new RegistrationException(
                    RegistrationException.Reason.ADMIN_ROLE_NOT_ALLOWED,
                    "Admin accounts cannot use public registration."
            );
        }
    }

    private void enforcePasswordLimits(String password) {
        if (password == null || password.isBlank() || password.length() < 8) {
            throw new RegistrationException(
                    RegistrationException.Reason.PASSWORD_TOO_SHORT,
                    "Password must contain at least 8 characters."
            );
        }
        int passwordBytes = password
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (passwordBytes > BCRYPT_MAX_PASSWORD_BYTES) {
            throw new RegistrationException(
                    RegistrationException.Reason.PASSWORD_TOO_LONG,
                    "Password must not exceed 72 UTF-8 bytes."
            );
        }
    }
}
