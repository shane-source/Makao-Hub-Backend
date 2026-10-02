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
                        null
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
                .andExpect(jsonPath("$.avatarUrl")
                        .doesNotExist());

        verify(profileService).getMyProfile(USER_ID);
    }
}