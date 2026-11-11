package com.makaohub.backend.profile.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.profile.service.AvatarUploadService;
import com.makaohub.backend.profile.service.ProfileService;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProfileController.class)
@Import(SecurityConfig.class)
class ProfileControllerTest {

    private static final UUID USER_ID =
            UUID.fromString(
                    "6b93d17b-0662-4718-a17d-736d85093e75"
            );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProfileService profileService;

    @MockitoBean
    private AvatarUploadService avatarUploadService;

    @MockitoBean
    private UploadSecurityProperties uploadSecurityProperties;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(
                        get("/api/v1/profile/me")
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(profileService);
    }

    @Test void photoCapabilitiesRequireAuthenticationAndExposeValidationVersion() throws Exception {
        mockMvc.perform(get("/api/v1/profile/me/avatar/capabilities"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/profile/me/avatar/capabilities")
                .with(jwt().jwt(jwt -> jwt.subject(USER_ID.toString())).authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        for (String role : new String[]{"RENTER", "LANDLORD", "SUBLESSOR"}) {
            mockMvc.perform(get("/api/v1/profile/me/avatar/capabilities")
                    .with(jwt().jwt(jwt -> jwt.subject(USER_ID.toString())).authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.validation").value("normalized-jpeg-png-v1"));
        }
    }

    @Test
    void usesAuthenticatedJwtSubjectAsUserId()
            throws Exception {
        MyProfileResponse response =
                new MyProfileResponse(
                        USER_ID,
                        "Wangari Maathai",
                        "wangari@example.com",
                        "+254712345678",
                        RoleName.LANDLORD,
                        AccountStatus.ACTIVE,
                        Instant.parse(
                                "2026-09-28T08:00:00Z"
                        ),
                        null,
                        com.makaohub.backend.verification.domain.VerificationStatus.NOT_STARTED
                );

        when(profileService.getMyProfile(USER_ID))
                .thenReturn(response);

        mockMvc.perform(
                        get("/api/v1/profile/me")
                                .with(jwt().jwt(jwt -> jwt
                                        .subject(USER_ID.toString())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(USER_ID.toString()))
                .andExpect(jsonPath("$.fullName")
                        .value("Wangari Maathai"))
                .andExpect(jsonPath("$.email")
                        .value("wangari@example.com"))
                .andExpect(jsonPath("$.phoneNumber")
                        .value("+254712345678"))
                .andExpect(jsonPath("$.role")
                        .value("LANDLORD"))
                .andExpect(jsonPath("$.accountStatus")
                        .value("ACTIVE"))
                .andExpect(jsonPath("$.verificationStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.avatarUrl")
                        .doesNotExist());

        verify(profileService).getMyProfile(USER_ID);
    }

    @Test void phoneCompletionRequiresAuthenticationAndPublicRole() throws Exception {
        mockMvc.perform(post("/api/v1/profile/me/phone/complete").contentType("application/json")
                .content("{\"phoneNumber\":\"0712345678\"}")).andExpect(status().isUnauthorized());
        for (String authority : new String[]{"ROLE_ADMIN", "SCOPE_read", "ROLE_UNKNOWN"}) {
            mockMvc.perform(post("/api/v1/profile/me/phone/complete").contentType("application/json")
                    .with(jwt().jwt(jwt -> jwt.subject(USER_ID.toString())).authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(authority)))
                    .content("{\"phoneNumber\":\"0712345678\"}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(profileService);
    }

    @Test void phoneCompletionUsesOnlyJwtOwnerAndValidatesPhoneForEveryPublicRole() throws Exception {
        for (String role : new String[]{"RENTER", "LANDLORD", "SUBLESSOR"}) {
            mockMvc.perform(post("/api/v1/profile/me/phone/complete").contentType("application/json")
                    .with(jwt().jwt(jwt -> jwt.subject(USER_ID.toString())).authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)))
                    .content("{\"phoneNumber\":\"0712345678\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isNoContent());
        }
        org.mockito.Mockito.verify(profileService, org.mockito.Mockito.times(3)).completeMissingPhone(USER_ID, "0712345678");
    }

    @Test void invalidPhoneAndMalformedJwtSubjectCannotReachCompletion() throws Exception {
        int address = 130;
        for (String phone : new String[]{"", " ", "invalid", "071234567890"}) {
            String ip = "192.0.2." + address++;
            mockMvc.perform(post("/api/v1/profile/me/phone/complete").contentType("application/json")
                    .with(request -> { request.setRemoteAddr(ip); return request; })
                    .with(jwt().jwt(jwt -> jwt.subject(USER_ID.toString())).authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_RENTER")))
                    .content("{\"phoneNumber\":\"" + phone + "\"}")).andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/v1/profile/me/phone/complete").contentType("application/json")
                .with(jwt().jwt(jwt -> jwt.subject("not-a-user-id")).authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_RENTER")))
                .content("{\"phoneNumber\":\"0712345678\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(profileService);
    }
}
