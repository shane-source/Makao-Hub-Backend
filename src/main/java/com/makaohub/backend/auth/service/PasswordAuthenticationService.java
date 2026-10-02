package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.api.AuthTokensResponse;
import com.makaohub.backend.auth.api.LoginRequest;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

@Service
public class PasswordAuthenticationService {

    private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationTokenService authenticationTokenService;
    private final String dummyPasswordHash;

    public PasswordAuthenticationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationTokenService authenticationTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationTokenService = authenticationTokenService;

        this.dummyPasswordHash = passwordEncoder.encode(
                "makao-hub-dummy-authentication-password"
        );
    }

    @Transactional
    public AuthTokensResponse authenticate(LoginRequest request) {
        rejectOversizedPassword(request.password());

        String normalizedEmail = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        Optional<User> userCandidate =
                userRepository.findByEmailIgnoreCase(
                        normalizedEmail
                );

        String passwordHash = userCandidate
                .map(User::getPasswordHash)
                .orElse(null);

        String hashForVerification =
                passwordHash == null
                        ? dummyPasswordHash
                        : passwordHash;

        boolean passwordMatches = passwordEncoder.matches(
                request.password(),
                hashForVerification
        );

        if (userCandidate.isEmpty()
                || passwordHash == null
                || !passwordMatches) {
            throw invalidCredentials();
        }

        User user = userCandidate.get();

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new AuthException(
                    AuthException.Reason.ACCOUNT_UNAVAILABLE,
                    "This account is unavailable."
            );
        }

        return authenticationTokenService.issue(user);
    }

    private void rejectOversizedPassword(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length
                > BCRYPT_MAX_PASSWORD_BYTES) {
            throw invalidCredentials();
        }
    }

    private AuthException invalidCredentials() {
        return new AuthException(
                AuthException.Reason.INVALID_CREDENTIALS,
                "The email or password is incorrect."
        );
    }
}