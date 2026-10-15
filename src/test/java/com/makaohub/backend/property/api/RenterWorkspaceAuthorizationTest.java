package com.makaohub.backend.property.api;
import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.property.service.*;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.verification.api.RenterVerificationController;
import com.makaohub.backend.verification.service.RenterVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest({RenterHomeController.class,RenterVerificationController.class})
@Import(SecurityConfig.class)
class RenterWorkspaceAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean RenterHomeService homes;
    @MockitoBean PublicListingService landlord;
    @MockitoBean PublicSublessorListingService sublessor;
    @MockitoBean RenterVerificationService evidence;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UploadSecurityProperties limits;
    @Test void wrongRolesAndAnonymousCannotUseRenterWorkspace() throws Exception {
        for(String path:List.of("/api/v1/renter/homes","/api/v1/renter/verification/documents")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for(String role:List.of("LANDLORD","SUBLESSOR"))
                mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role)))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(homes,evidence);
    }
    @Test void savedHomeAndEvidenceIdentityComeFromJwt() throws Exception {
        UUID actor=UUID.randomUUID(),id=UUID.randomUUID();
        var token=jwt().jwt(j->j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_RENTER"));
        mvc.perform(put("/api/v1/renter/homes/SUBLESSOR/{id}/save",id).param("renterId",UUID.randomUUID().toString()).with(token)).andExpect(status().isNoContent());
        verify(homes).save(actor,"SUBLESSOR",id);
        mvc.perform(get("/api/v1/renter/verification/documents").with(token)).andExpect(status().isOk());
        verify(evidence).getMyDocuments(actor);
    }
}
