package com.makaohub.backend.payment.paystack.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.payment.paystack.service.BookingTestPayoutService;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OwnerTestPayoutDestinationController.class) @Import(SecurityConfig.class) @ActiveProfiles("paystack")
class OwnerTestPayoutDestinationAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean BookingTestPayoutService payouts;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UploadSecurityProperties limits;
    private static final String BODY="{\"phone\":\"0712345678\",\"ownNumberConfirmed\":true}";
    @Test void onlyCurrentOwnerRoleMayConfigureOwnDestination() throws Exception {
        UUID owner=UUID.randomUUID(),key=UUID.randomUUID();
        String landlord="/api/v1/landlord/payments/test-destination",sublessor="/api/v1/sublessor/payments/test-destination";
        mvc.perform(post(landlord).contentType("application/json").content(BODY).header("Idempotency-Key",key))
            .andExpect(status().isUnauthorized());
        for(String role:List.of("RENTER","ADMIN","SUBLESSOR"))
            mvc.perform(post(landlord).with(jwt().jwt(j->j.subject(owner.toString()).claim("roles",List.of(role)))
                .authorities(new SimpleGrantedAuthority("ROLE_"+role))).contentType("application/json").content(BODY)
                .header("Idempotency-Key",key)).andExpect(status().isForbidden());
        var landlordToken=jwt().jwt(j->j.subject(owner.toString()).claim("roles",List.of("LANDLORD")))
            .authorities(new SimpleGrantedAuthority("ROLE_LANDLORD"));
        mvc.perform(post(sublessor).with(landlordToken).contentType("application/json").content(BODY)
            .header("Idempotency-Key",key)).andExpect(status().isForbidden());
        verifyNoInteractions(payouts);
        when(payouts.configureOwnMpesa(owner,key,"0712345678")).thenReturn(Map.of("configured",true));
        mvc.perform(post(landlord).with(landlordToken).contentType("application/json").content(BODY)
            .header("Idempotency-Key",key)).andExpect(status().isOk());
        verify(payouts).configureOwnMpesa(owner,key,"0712345678");
    }
    @Test void invalidNumberAndMissingConsentNeverReachProvider() throws Exception {
        UUID owner=UUID.randomUUID();
        var token=jwt().jwt(j->j.subject(owner.toString()).claim("roles",List.of("SUBLESSOR")))
            .authorities(new SimpleGrantedAuthority("ROLE_SUBLESSOR"));
        for(String body:List.of("{\"phone\":\"0712345678\",\"ownNumberConfirmed\":false}",
                "{\"phone\":\"123\",\"ownNumberConfirmed\":true}"))
            mvc.perform(post("/api/v1/sublessor/payments/test-destination").with(token)
                .contentType("application/json").content(body).header("Idempotency-Key",UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(payouts);
    }
}
