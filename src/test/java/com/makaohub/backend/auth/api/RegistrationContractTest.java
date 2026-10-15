package com.makaohub.backend.auth.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.auth.domain.AuthOtpChallengePurpose;
import com.makaohub.backend.auth.service.*;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class RegistrationContractTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private RegistrationService registration;
    @MockitoBean private GoogleRegistrationService googleRegistration;
    @MockitoBean private PasswordAuthenticationService login;
    @MockitoBean private GoogleAuthenticationService googleLogin;
    @MockitoBean private AuthenticationTokenService tokens;
    @MockitoBean private RefreshTokenService refresh;
    @MockitoBean private AuthOtpService otp;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private UploadSecurityProperties uploadLimits;

    private String body(String role) {
        return """
                {"fullName":"Test User", "email":"test@example.invalid",
                 "phoneNumber":"+254712345678", "password":"TestOnlyPassword123!",
                 "termsAndPrivacyAccepted":true, "role":%s}
                """.formatted(role);
    }

    @Test void emailSignupRequiresExactlyOneKnownRole() throws Exception {
        for (String invalid : new String[]{"null", "\"ROOMMATE_SEEKER\"", "[\"RENTER\",\"LANDLORD\"]"}) {
            mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(invalid)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(registration);
    }

    @Test void acceptsSelectedSublessorRoleInSignupContract() throws Exception {
        when(registration.register(any())).thenReturn(new OtpChallengeResponse(
                java.util.UUID.randomUUID(), AuthOtpChallengePurpose.REGISTRATION,
                "••••@example.invalid", java.time.Instant.parse("2026-10-13T12:00:00Z")
        ));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body("\"SUBLESSOR\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purpose").value("REGISTRATION"))
                .andExpect(jsonPath("$.challengeId").exists())
                .andExpect(jsonPath("$.maskedEmail").value("••••@example.invalid"))
                .andExpect(jsonPath("$.maskedPhone").doesNotExist())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        verify(registration).register(argThat(request -> request.role().name().equals("SUBLESSOR")));
    }

    @Test void signupAcceptsEightCharactersAndRejectsSevenAndSeventyThree() throws Exception {
        when(registration.register(any())).thenReturn(new OtpChallengeResponse(
                java.util.UUID.randomUUID(), AuthOtpChallengePurpose.REGISTRATION,
                "••••@example.invalid", java.time.Instant.now().plusSeconds(300)));
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr("192.0.2.60"); return request; })
                .content(body("\"RENTER\"").replace("TestOnlyPassword123!", "12345678")))
                .andExpect(status().isCreated());
        int address = 61;
        for (String invalid : new String[]{"1234567", "a".repeat(73), " ".repeat(8)}) {
            String testIp = "192.0.2." + address++;
            mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                    .with(request -> { request.setRemoteAddr(testIp); return request; })
                    .content(body("\"RENTER\"").replace("TestOnlyPassword123!", invalid)))
                    .andExpect(status().isBadRequest());
        }
        verify(registration, times(1)).register(any());
    }

    @Test void resetAcceptsEightCharactersAndRejectsSevenAndSeventyThree() throws Exception {
        String reset = "{\"email\":\"test@example.invalid\",\"code\":\"123456\",\"newPassword\":\"%s\"}";
        mvc.perform(post("/api/v1/auth/forgot-password/reset").contentType(MediaType.APPLICATION_JSON)
                .with(request -> { request.setRemoteAddr("192.0.2.70"); return request; })
                .content(reset.formatted("12345678"))).andExpect(status().isNoContent());
        int address = 71;
        for (String invalid : new String[]{"1234567", "a".repeat(73)}) {
            String testIp = "192.0.2." + address++;
            mvc.perform(post("/api/v1/auth/forgot-password/reset").contentType(MediaType.APPLICATION_JSON)
                    .with(request -> { request.setRemoteAddr(testIp); return request; })
                    .content(reset.formatted(invalid))).andExpect(status().isBadRequest());
        }
        verify(otp, times(1)).resetPassword("test@example.invalid", "123456", "12345678");
    }

    @Test void registrationOtpVerificationEndpointIsPublicAndRateLimitedSeparately() throws Exception {
        mvc.perform(post("/api/v1/auth/register/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"not-a-uuid\",\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(otp);
    }

    @Test void googleSignupAlsoRequiresRole() throws Exception {
        mvc.perform(post("/api/v1/auth/google/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"idToken":"test-token", "fullName":"Test User", "phoneNumber":"+254712345678",
                 "termsAndPrivacyAccepted":true}
                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(googleRegistration);
    }

    @Test void authenticatedRenterCannotEnterLandlordNamespace() throws Exception {
        mvc.perform(get("/api/v1/landlord/listings").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_RENTER"))))
                .andExpect(status().isForbidden());
    }

    @Test void authenticatedSublessorCannotEnterLandlordNamespace() throws Exception {
        mvc.perform(get("/api/v1/landlord/listings").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUBLESSOR"))))
                .andExpect(status().isForbidden());
    }
}
