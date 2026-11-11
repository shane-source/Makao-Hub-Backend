package com.makaohub.backend.profile.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.auth.domain.AuthOtpChallengePurpose;
import com.makaohub.backend.profile.service.AccountLifecycleService;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountLifecycleController.class)
@Import(SecurityConfig.class)
class AccountLifecycleControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountLifecycleService lifecycle;
    @MockitoBean UploadSecurityProperties uploadSecurityProperties;
    @MockitoBean JwtDecoder jwtDecoder;
    private final UUID userId = UUID.randomUUID();

    @Test void requiresAuthenticatedPublicRole() throws Exception {
        String body = "{\"email\":\"user@example.test\",\"action\":\"DELETE\"}";
        mvc.perform(post("/api/v1/profile/me/account-action/start")
                .contentType("application/json").content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/profile/me/account-action/start")
                .with(jwt().jwt(j -> j.subject(userId.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(lifecycle);
    }

    @Test void usesJwtOwnerNotClientSuppliedUserId() throws Exception {
        UUID other = UUID.randomUUID();
        mvc.perform(post("/api/v1/profile/me/account-action/start")
                .with(jwt().jwt(j -> j.subject(userId.toString())).authorities(new SimpleGrantedAuthority("ROLE_RENTER")))
                .contentType("application/json")
                .content("{\"email\":\"user@example.test\",\"action\":\"DELETE\",\"userId\":\"" + other + "\"}"))
                .andExpect(status().isOk());
        verify(lifecycle).start(userId, "user@example.test", AuthOtpChallengePurpose.ACCOUNT_DELETION);
    }

    @Test void rejectsMalformedEmailAndCodeBeforeService() throws Exception {
        mvc.perform(post("/api/v1/profile/me/account-action/confirm")
                .with(jwt().jwt(j -> j.subject(userId.toString())).authorities(new SimpleGrantedAuthority("ROLE_LANDLORD")))
                .contentType("application/json")
                .content("{\"email\":\"not-email\",\"action\":\"DEACTIVATE\",\"challengeId\":\"x\",\"code\":\"123\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(lifecycle);
    }
}
