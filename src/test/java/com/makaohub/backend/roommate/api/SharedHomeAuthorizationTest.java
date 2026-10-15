package com.makaohub.backend.roommate.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.property.api.SublessorHouseholdController;
import com.makaohub.backend.property.service.SublessorHouseholdService;
import com.makaohub.backend.roommate.service.RoommatePreferencesService;
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
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({RoommatePreferencesController.class,SublessorHouseholdController.class})
@Import(SecurityConfig.class)
class SharedHomeAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean RoommatePreferencesService preferences;
    @MockitoBean SublessorHouseholdService household;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UploadSecurityProperties limits;
    @Test void discoveryRejectsAnonymousAndOtherActiveRoles() throws Exception {
        mvc.perform(get("/api/v1/sublessor/roommate-discovery")).andExpect(status().isUnauthorized());
        for(String role:List.of("RENTER","LANDLORD")) mvc.perform(get("/api/v1/sublessor/roommate-discovery")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role)))).andExpect(status().isForbidden());
        verifyNoInteractions(preferences);
    }
    @Test void householdIdentityComesOnlyFromJwt() throws Exception {
        UUID actor=UUID.randomUUID(),listing=UUID.randomUUID();
        mvc.perform(get("/api/v1/sublessor/listings/{id}/household",listing).param("sublessorId",UUID.randomUUID().toString())
            .with(jwt().jwt(j->j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_SUBLESSOR"))))
            .andExpect(status().isOk());
        verify(household).get(actor,listing);
    }
    @Test void invalidBudgetsAndPreferenceValuesAreRejectedBeforeService() throws Exception {
        String body="""
            {"quietSocial":"QUIET","sleepSchedule":"EARLY","cleanliness":"DAILY","cooking":"REGULAR","guests":"ASK_FIRST",
            "smoking":"NO_INDOOR","pets":"NONE","budgetMinKes":22000,"budgetMaxKes":15000,"discoveryOptIn":true}
            """;
        var actor=jwt().jwt(j->j.subject(UUID.randomUUID().toString())).authorities(new SimpleGrantedAuthority("ROLE_SUBLESSOR"));
        mvc.perform(put("/api/v1/sublessor/roommate-preferences").with(actor).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/sublessor/roommate-preferences").with(actor).contentType(MediaType.APPLICATION_JSON)
            .content(body.replace("\"QUIET\"","\"ARBITRARY\"").replace("\"budgetMaxKes\":15000","\"budgetMaxKes\":25000"))).andExpect(status().isBadRequest());
        verifyNoInteractions(preferences);
    }
}
