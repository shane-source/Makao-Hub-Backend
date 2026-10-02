package com.makaohub.backend;

import com.makaohub.backend.auth.api.AuthTokensResponse;
import com.makaohub.backend.auth.api.LoginRequest;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.service.AuthenticationTokenService;
import com.makaohub.backend.auth.service.PasswordAuthenticationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordAuthenticationServiceTest {

    private static final String CORRECT_PASSWORD =
            "StrongPassword123!";

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthenticationTokenService authenticationTokenService;

    private PasswordEncoder passwordEncoder;
    private PasswordAuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);

        authenticationService =
                new PasswordAuthenticationService(
                        userRepository,
                        passwordEncoder,
                        authenticationTokenService
                );
    }

    @Test
    void validCredentialsIssueTokens() {
        User user = mock(User.class);

        when(user.getPasswordHash()).thenReturn(
                passwordEncoder.encode(CORRECT_PASSWORD)
        );

        when(user.getAccountStatus()).thenReturn(
                AccountStatus.ACTIVE
        );

        when(userRepository.findByEmailIgnoreCase(
                "tenant.test1@example.com"
        )).thenReturn(Optional.of(user));

        AuthTokensResponse expectedResponse =
                mock(AuthTokensResponse.class);

        when(authenticationTokenService.issue(user))
                .thenReturn(expectedResponse);

        AuthTokensResponse actualResponse =
                authenticationService.authenticate(
                        new LoginRequest(
                                "TENANT.TEST1@EXAMPLE.COM",
                                CORRECT_PASSWORD
                        )
                );

        assertSame(expectedResponse, actualResponse);

        verify(authenticationTokenService).issue(user);
    }

    @Test
    void incorrectPasswordReturnsGenericError() {
        User user = mock(User.class);

        when(user.getPasswordHash()).thenReturn(
                passwordEncoder.encode(CORRECT_PASSWORD)
        );

        when(userRepository.findByEmailIgnoreCase(
                "tenant.test1@example.com"
        )).thenReturn(Optional.of(user));

        AuthException exception = assertThrows(
                AuthException.class,
                () -> authenticationService.authenticate(
                        new LoginRequest(
                                "tenant.test1@example.com",
                                "WrongPassword123!"
                        )
                )
        );

        assertEquals(
                AuthException.Reason.INVALID_CREDENTIALS,
                exception.getReason()
        );

        assertEquals(
                "The email or password is incorrect.",
                exception.getMessage()
        );

        verify(
                authenticationTokenService,
                never()
        ).issue(user);
    }
}