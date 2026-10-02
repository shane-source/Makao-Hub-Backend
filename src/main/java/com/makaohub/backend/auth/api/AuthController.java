package com.makaohub.backend.auth.api;

import com.makaohub.backend.auth.service.AuthenticationTokenService;
import com.makaohub.backend.auth.service.GoogleAuthenticationService;
import com.makaohub.backend.auth.service.GoogleRegistrationService;
import com.makaohub.backend.auth.service.PasswordAuthenticationService;
import com.makaohub.backend.auth.service.RefreshTokenService;
import com.makaohub.backend.auth.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegistrationService registrationService;
    private final PasswordAuthenticationService passwordAuthenticationService;
    private final GoogleAuthenticationService googleAuthenticationService;
    private final GoogleRegistrationService googleRegistrationService;
    private final AuthenticationTokenService authenticationTokenService;
    private final RefreshTokenService refreshTokenService;

    public AuthController(
            RegistrationService registrationService,
            PasswordAuthenticationService passwordAuthenticationService,
            GoogleAuthenticationService googleAuthenticationService,
            GoogleRegistrationService googleRegistrationService,
            AuthenticationTokenService authenticationTokenService,
            RefreshTokenService refreshTokenService
    ) {
        this.registrationService = registrationService;
        this.passwordAuthenticationService =
                passwordAuthenticationService;
        this.googleAuthenticationService =
                googleAuthenticationService;
        this.googleRegistrationService =
                googleRegistrationService;
        this.authenticationTokenService =
                authenticationTokenService;
        this.refreshTokenService = refreshTokenService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return registrationService.register(request);
    }

    @PostMapping("/login")
    public AuthTokensResponse login(
            @Valid @RequestBody LoginRequest request
    ) {
        return passwordAuthenticationService.authenticate(
                request
        );
    }

    @PostMapping("/refresh")
    public AuthTokensResponse refresh(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        return authenticationTokenService.refresh(
                request.refreshToken()
        );
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @Valid @RequestBody RefreshTokenRequest request
    ) {
        refreshTokenService.revokeTokenFamily(
                request.refreshToken()
        );
    }

    @PostMapping("/google/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthTokensResponse registerWithGoogle(
            @Valid @RequestBody GoogleRegistrationRequest request
    ) {
        return googleRegistrationService.register(request);
    }

    @PostMapping("/google")
    public AuthTokensResponse authenticateWithGoogle(
            @Valid @RequestBody GoogleAuthRequest request
    ) {
        return googleAuthenticationService.authenticate(
                request.idToken()
        );
    }
}