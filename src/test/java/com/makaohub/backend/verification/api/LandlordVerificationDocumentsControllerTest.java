package com.makaohub.backend.verification.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.verification.domain.*;
import com.makaohub.backend.verification.service.LandlordVerificationService;
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

@WebMvcTest(LandlordVerificationController.class)
@Import(SecurityConfig.class)
class LandlordVerificationDocumentsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean LandlordVerificationService service;
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
        when(service.getMyDocuments(owner)).thenReturn(List.of(new LandlordVerificationDocumentResponse(
                document, VerificationDocumentType.ID_FRONT, VerificationDocumentStatus.READY, "image/jpeg", 100L, false)));
        mvc.perform(get("/api/v1/landlord/verification/documents").param("landlordId", UUID.randomUUID().toString())
                .with(jwt().jwt(token -> token.subject(owner.toString())).authorities(new SimpleGrantedAuthority("ROLE_LANDLORD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].documentId").value(document.toString()))
                .andExpect(jsonPath("$[0].documentType").value("ID_FRONT"))
                .andExpect(jsonPath("$[0].status").value("READY"))
                .andExpect(jsonPath("$[0].objectPath").doesNotExist())
                .andExpect(jsonPath("$[0].uploadUrl").doesNotExist())
                .andExpect(jsonPath("$[0].readUrl").doesNotExist());
        verify(service).getMyDocuments(owner);
    }
    @Test void renterAndSublessorAreForbidden() throws Exception {
        for (String role : List.of("RENTER", "SUBLESSOR")) {
            mvc.perform(get("/api/v1/landlord/verification/documents")
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void renterAndSublessorCannotCreateConfirmOrSubmitEvidence() throws Exception {
        UUID document = UUID.randomUUID();
        for (String role : List.of("RENTER", "SUBLESSOR")) {
            var wrongRole = jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
            mvc.perform(post("/api/v1/landlord/verification/documents/upload-url")
                            .with(wrongRole).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"documentType\":\"OWNERSHIP_PROOF\",\"contentType\":\"application/pdf\",\"replaceExisting\":false}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/landlord/verification/documents/{id}/confirm", document).with(wrongRole))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/landlord/verification/submit").with(wrongRole))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void landlordWritesUseOnlyTheJwtOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        var landlord = jwt().jwt(token -> token.subject(owner.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_LANDLORD"));
        when(service.createDocumentUploadUrl(owner, VerificationDocumentType.OWNERSHIP_PROOF,
                "application/pdf", true)).thenReturn(new VerificationDocumentUploadUrlResponse(
                document, VerificationDocumentType.OWNERSHIP_PROOF, "application/pdf", "https://synthetic.invalid/upload"));

        mvc.perform(post("/api/v1/landlord/verification/documents/upload-url")
                        .param("landlordId", UUID.randomUUID().toString()).with(landlord)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"OWNERSHIP_PROOF\",\"contentType\":\"application/pdf\",\"replaceExisting\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value(document.toString()));
        mvc.perform(post("/api/v1/landlord/verification/documents/{id}/confirm", document).with(landlord))
                .andExpect(status().isNoContent());

        verify(service).createDocumentUploadUrl(owner, VerificationDocumentType.OWNERSHIP_PROOF,
                "application/pdf", true);
        verify(service).confirmDocumentUpload(owner, document);
    }
    @Test void anonymousCallerIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/landlord/verification/documents")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
