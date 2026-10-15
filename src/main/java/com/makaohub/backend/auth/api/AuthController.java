package com.makaohub.backend.auth.api;

import com.makaohub.backend.auth.service.AuthenticationTokenService;
import com.makaohub.backend.auth.service.AuthOtpService;
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
    private final AuthOtpService authOtpService;

    public AuthController(
            RegistrationService registrationService,
            PasswordAuthenticationService passwordAuthenticationService,
            GoogleAuthenticationService googleAuthenticationService,
            GoogleRegistrationService googleRegistrationService,
            AuthenticationTokenService authenticationTokenService,
            RefreshTokenService refreshTokenService,
            AuthOtpService authOtpService
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
        this.authOtpService = authOtpService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public OtpChallengeResponse register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return registrationService.register(request);
    }

    @PostMapping("/register/verify-otp")
    public AuthTokensResponse verifyRegistrationOtp(
            @Valid @RequestBody OtpVerifyRequest request
    ) {
        return authOtpService.verifyRegistration(
                request.parsedChallengeId(), request.code()
        );
    }

    @PostMapping("/login")
    public OtpChallengeResponse login(
            @Valid @RequestBody LoginRequest request
    ) {
        return authOtpService.startLogin(
                passwordAuthenticationService.verifyCredentials(request)
        );
    }

    @PostMapping("/login/verify-otp")
    public AuthTokensResponse verifyLoginOtp(
            @Valid @RequestBody OtpVerifyRequest request
    ) {
        return authOtpService.verifyLogin(request.parsedChallengeId(), request.code());
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PasswordResetAcceptedResponse forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request
    ) {
        return authOtpService.requestPasswordReset(request.email());
    }

    @PostMapping("/forgot-password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        authOtpService.resetPassword(request.email(), request.code(), request.newPassword());
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
    public OtpChallengeResponse registerWithGoogle(
            @Valid @RequestBody GoogleRegistrationRequest request
    ) {
        return googleRegistrationService.register(request);
    }

    @PostMapping("/google")
    public OtpChallengeResponse authenticateWithGoogle(
            @Valid @RequestBody GoogleAuthRequest request
    ) {
        return authOtpService.startLogin(
                googleAuthenticationService.verifyIdentity(request.idToken())
        );
    }

    @PostMapping("/google/verify-otp")
    public AuthTokensResponse verifyGoogleOtp(
            @Valid @RequestBody OtpVerifyRequest request
    ) {
        return authOtpService.verifyLogin(request.parsedChallengeId(), request.code());
    }
}
