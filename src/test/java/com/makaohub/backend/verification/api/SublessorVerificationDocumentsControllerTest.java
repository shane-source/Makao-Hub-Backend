package com.makaohub.backend.verification.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.verification.domain.*;
import com.makaohub.backend.verification.service.SublessorVerificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SublessorVerificationController.class)
@Import(SecurityConfig.class)
class SublessorVerificationDocumentsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean SublessorVerificationService service;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UploadSecurityProperties uploadLimits;

    @BeforeEach void rateLimits() {
        when(uploadLimits.avatarUploadUrlsPerHour()).thenReturn(100L);
        when(uploadLimits.avatarConfirmationsPerHour()).thenReturn(100L);
        when(uploadLimits.verificationUploadUrlsPerHour()).thenReturn(100L);
        when(uploadLimits.verificationConfirmationsPerHour()).thenReturn(100L);
        when(uploadLimits.verificationSubmissionsPerDay()).thenReturn(100L);
    }

    @Test void callerCannotSelectAnotherOwnerAndOnlyMetadataIsReturned() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        when(service.getMyDocuments(owner)).thenReturn(List.of(new SublessorVerificationDocumentResponse(
                document, VerificationDocumentType.ID_FRONT, VerificationDocumentStatus.READY, "image/jpeg", 100L, false)));
        mvc.perform(get("/api/v1/sublessor/verification/documents").param("sublessorId", UUID.randomUUID().toString())
                .with(jwt().jwt(token -> token.subject(owner.toString())).authorities(new SimpleGrantedAuthority("ROLE_SUBLESSOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentId").value(document.toString()))
                .andExpect(jsonPath("$[0].documentType").value("ID_FRONT"))
                .andExpect(jsonPath("$[0].status").value("READY"))
                .andExpect(jsonPath("$[0].objectPath").doesNotExist())
                .andExpect(jsonPath("$[0].uploadUrl").doesNotExist())
                .andExpect(jsonPath("$[0].readUrl").doesNotExist());
        verify(service).getMyDocuments(owner);
    }
    @Test void renterAndLandlordAreForbidden() throws Exception {
        for (String role : List.of("RENTER", "LANDLORD")) {
            mvc.perform(get("/api/v1/sublessor/verification/documents")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void renterAndLandlordCannotCreateConfirmOrSubmitEvidence() throws Exception {
        UUID document = UUID.randomUUID();
        for (String role : List.of("RENTER", "LANDLORD")) {
            var wrongRole = jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
            mvc.perform(post("/api/v1/sublessor/verification/documents/upload-url")
                            .with(wrongRole).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"documentType\":\"RENTAL_AGREEMENT\",\"contentType\":\"application/pdf\",\"replaceExisting\":false}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/sublessor/verification/documents/{id}/confirm", document).with(wrongRole))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/sublessor/verification/submit").with(wrongRole))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void sublessorWritesUseOnlyTheJwtOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        var sublessor = jwt().jwt(token -> token.subject(owner.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_SUBLESSOR"));
        when(service.createDocumentUploadUrl(owner, VerificationDocumentType.RENTAL_AGREEMENT,
                "application/pdf", true)).thenReturn(new VerificationDocumentUploadUrlResponse(
                document, VerificationDocumentType.RENTAL_AGREEMENT, "application/pdf", "https://synthetic.invalid/upload"));

        mvc.perform(post("/api/v1/sublessor/verification/documents/upload-url")
                        .param("sublessorId", UUID.randomUUID().toString()).with(sublessor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"RENTAL_AGREEMENT\",\"contentType\":\"application/pdf\",\"replaceExisting\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(document.toString()));
        mvc.perform(post("/api/v1/sublessor/verification/documents/{id}/confirm", document).with(sublessor))
                .andExpect(status().isNoContent());

        verify(service).createDocumentUploadUrl(owner, VerificationDocumentType.RENTAL_AGREEMENT,
                "application/pdf", true);
        verify(service).confirmDocumentUpload(owner, document);
    }
    @Test void anonymousCallerIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/sublessor/verification/documents")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
