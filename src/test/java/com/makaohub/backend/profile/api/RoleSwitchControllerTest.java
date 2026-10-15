package com.makaohub.backend.profile.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.profile.service.RoleSwitchService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = RoleSwitchController.class,
        properties = "makao.role-switch.enabled=true"
)
@Import(SecurityConfig.class)
class RoleSwitchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RoleSwitchService roleSwitchService;

    @MockitoBean
    private UploadSecurityProperties uploadSecurityProperties;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void switchRequiresAuthentication() throws Exception {
        mockMvc.perform(
                        post("/api/v1/profile/me/role/switch")
                                .contentType("application/json")
                                .content("""
                                        {
                                          "targetRole": "RENTER",
                                          "currentPassword": "test-password"
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(roleSwitchService);
    }

    @Test
    void adminRoleIsRejected() throws Exception {
        mockMvc.perform(
                        post("/api/v1/profile/me/role/switch")
                                .with(jwt())
                                .contentType("application/json")
                                .content("""
                                        {
                                          "targetRole": "ADMIN",
                                          "currentPassword": "test-password"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(roleSwitchService);
    }

    @Test
    void googleChallengeResponseIsNotCacheable() throws Exception {
        GoogleRoleSwitchChallengeResponse response =
                new GoogleRoleSwitchChallengeResponse(
                        UUID.randomUUID(),
                        RoleName.RENTER,
                        "a".repeat(43),
                        Instant.parse("2026-10-05T00:05:00Z")
                );

        when(roleSwitchService.issueGoogleChallenge(
                any(),
                eq(RoleName.RENTER)
        )).thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/profile/me/role/google-challenge")
                                .with(jwt())
                                .contentType("application/json")
                                .content("""
                                        {
                                          "targetRole": "RENTER"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                "no-store"
                        )
                );
    }
}