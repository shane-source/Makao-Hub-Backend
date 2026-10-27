package com.makaohub.backend.admin;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StaffAuthController.class)
@Import(SecurityConfig.class)
class StaffInvitationAcceptanceHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean StaffAuthService auth;
    @MockitoBean StaffInvitationService invitations;
    @MockitoBean StaffAccessService access;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UploadSecurityProperties uploads;

    private static final String BODY="""
            {"email":"lead+staff@example.invalid","invitationCode":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
             "fullName":"Synthetic Lead","password":"TestPassword!"}
            """;

    @Test void unmatchedInvitationReturnsStructuredGuidanceWithoutErrorRedispatch() throws Exception {
        when(invitations.accept(any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,"Record not found."));
        mvc.perform(post("/api/v1/staff/auth/accept-invitation").contentType("application/json").content(BODY))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("STAFF_INVITATION_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.detail").value(containsString("exact email address")))
                .andExpect(jsonPath("$.detail").value(containsString("+suffix")))
                .andExpect(result->assertNull(result.getResponse().getErrorMessage()));
    }

    @Test void conflictDoesNotExposePrivateExceptionDetails() throws Exception {
        when(invitations.accept(any())).thenThrow(AdminStore.conflict("synthetic-private-value"));
        mvc.perform(post("/api/v1/staff/auth/accept-invitation").contentType("application/json").content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("sign in")))
                .andExpect(content().string(not(containsString("synthetic-private-value"))));
    }

    @Test void successfulAcceptanceKeepsTheExistingSignInRequiredContract() throws Exception {
        when(invitations.accept(any())).thenReturn(Map.of("userId",UUID.randomUUID(),"nextStep","SIGN_IN_WITH_EMAIL_OTP"));
        mvc.perform(post("/api/v1/staff/auth/accept-invitation").contentType("application/json").content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextStep").value("SIGN_IN_WITH_EMAIL_OTP"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        verifyNoInteractions(auth);
    }

    @Test void invalidFormIsRejectedBeforeCallingTheInvitationService() throws Exception {
        mvc.perform(post("/api/v1/staff/auth/accept-invitation").contentType("application/json")
                        .content(BODY.replace("TestPassword!","short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(invitations);
    }
}
