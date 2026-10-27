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
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminSecurityBoundaryTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminWorkflowService work;
    @MockitoBean StaffInvitationService invitations;
    @MockitoBean StaffAccessService access;
    @MockitoBean UserReportService reports;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UploadSecurityProperties uploads;

    @Test void unauthenticatedRequestCannotReadAdminQueues() throws Exception {
        mvc.perform(get("/api/v1/admin/verifications")).andExpect(status().isUnauthorized());
        verifyNoInteractions(work);
    }
    @Test void adminRoleWithoutRegisteredMfaProofIsDenied() throws Exception {
        when(access.allows(any())).thenReturn(false);
        mvc.perform(get("/api/v1/admin/verifications").with(jwt().jwt(j->j.claim("roles",List.of("ADMIN")))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(work);
    }
    @Test void activeStaffCanReachItsPermissionedServiceAndFinancialWritesStayBlocked() throws Exception {
        when(access.allows(any())).thenReturn(true);
        when(work.verificationQueue(any(),any(),any(),any(),anyInt(),anyInt())).thenReturn(new AdminStore.Page(List.of(),0,20,false));
        mvc.perform(get("/api/v1/admin/verifications").with(jwt().jwt(j->j.claim("roles",List.of("ADMIN")))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/payments/bookings/"+UUID.randomUUID()+"/release")
                        .with(jwt().jwt(j->j.claim("roles",List.of("ADMIN")))))
                .andExpect(status().isForbidden());
    }

    @Test void authenticatedListingMediaIsInlinePrivateAndNotCacheable() throws Exception {
        UUID listing=UUID.randomUUID(), media=UUID.randomUUID();
        when(access.allows(any())).thenReturn(true);
        when(work.listingMedia(any(),eq("LANDLORD"),eq(listing),eq(media)))
                .thenReturn(new AdminWorkflowService.Document(new byte[]{1,2,3},"image/jpeg"));

        mvc.perform(get("/api/v1/admin/listings/LANDLORD/"+listing+"/media/"+media)
                        .with(jwt().jwt(j->j.claim("roles",List.of("ADMIN")))))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(new byte[]{1,2,3}))
                .andExpect(header().string("Cache-Control",containsString("no-store")))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(header().string("Content-Disposition",containsString("inline")));
    }

    private static final String INVITE="""
            {"email":"reviewer@example.invalid","role":"VERIFICATION_REVIEWER",
             "documentReview":true,"reason":"Assign identity verification duties"}
            """;

    @Test void existingAccountInvitationReturnsSafeConflictWithoutErrorRedispatch() throws Exception {
        when(access.allows(any())).thenReturn(true);
        when(invitations.invite(any(),any(),any())).thenThrow(AdminStore.conflict(
                "Use a separate staff email; an existing account will not be promoted."));
        mvc.perform(post("/api/v1/admin/staff/invitations").with(jwt().jwt(j->j.claim("roles",List.of("ADMIN"))))
                        .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(INVITE))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(containsString("separate staff email")))
                .andExpect(result->assertNull(result.getResponse().getErrorMessage()));
    }

    @Test void unknownFailureReasonCannotExposePrivateData() throws Exception {
        when(access.allows(any())).thenReturn(true);
        when(invitations.invite(any(),any(),any())).thenThrow(AdminStore.conflict("synthetic-private-value"));
        mvc.perform(post("/api/v1/admin/staff/invitations").with(jwt().jwt(j->j.claim("roles",List.of("ADMIN"))))
                        .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(INVITE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STAFF_REQUEST_REJECTED"))
                .andExpect(content().string(not(containsString("synthetic-private-value"))));
    }

    @Test void servicePermissionDenialRemainsForbiddenWithSafeBody() throws Exception {
        when(access.allows(any())).thenReturn(true);
        when(invitations.invite(any(),any(),any())).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,"synthetic-private-value"));
        mvc.perform(post("/api/v1/admin/staff/invitations").with(jwt().jwt(j->j.claim("roles",List.of("ADMIN"))))
                        .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(INVITE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("permission")))
                .andExpect(content().string(not(containsString("synthetic-private-value"))));
    }

    @Test void invitationWithoutMfaNeverReachesInvitationService() throws Exception {
        when(access.allows(any())).thenReturn(false);
        mvc.perform(post("/api/v1/admin/staff/invitations").with(jwt().jwt(j->j.claim("roles",List.of("ADMIN"))))
                        .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json").content(INVITE))
                .andExpect(status().isForbidden());
        verifyNoInteractions(invitations);
    }

    @Test void unviewedDocumentConflictKeepsItsActionableSafeReason() throws Exception {
        when(access.allows(any())).thenReturn(true);
        when(work.markDocumentReviewed(any(),any(),any(),any(),any())).thenThrow(AdminStore.conflict(
                "View the current document before marking it reviewed."));
        mvc.perform(post("/api/v1/admin/verifications/LANDLORD/"+UUID.randomUUID()+"/documents/"+UUID.randomUUID()+"/review")
                        .with(jwt().jwt(j->j.claim("roles",List.of("ADMIN"))))
                        .header("Idempotency-Key",UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("View the current document before marking it reviewed."))
                .andExpect(result->assertNull(result.getResponse().getErrorMessage()));
    }
}
