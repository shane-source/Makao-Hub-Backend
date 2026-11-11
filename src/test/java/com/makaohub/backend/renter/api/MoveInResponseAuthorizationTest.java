package com.makaohub.backend.renter.api;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.renter.service.BookingAgreementService;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RenterBookingAgreementController.class) @Import(SecurityConfig.class)
class MoveInResponseAuthorizationTest {
    @Autowired MockMvc mvc;
    @MockitoBean BookingAgreementService agreements;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean UploadSecurityProperties limits;
    private final UUID booking=UUID.randomUUID();
    @Test void anonymousAndOtherRolesCannotAnswer() throws Exception {
        String path="/api/v1/renter/bookings/"+booking+"/move-in/response";
        mvc.perform(post(path).contentType("application/json").content("{\"accepted\":true}").header("Idempotency-Key","test"))
                .andExpect(status().isUnauthorized());
        for(String role:List.of("LANDLORD","SUBLESSOR","ADMIN"))
            mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_"+role)))
                    .contentType("application/json").content("{\"accepted\":true}").header("Idempotency-Key","test"))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(agreements);
    }
    @Test void responseRequiresBooleanAndKeyAndIdentityAlwaysComesFromJwt() throws Exception {
        UUID renter=UUID.randomUUID();
        var token=jwt().jwt(j->j.subject(renter.toString())).authorities(new SimpleGrantedAuthority("ROLE_RENTER"));
        String path="/api/v1/renter/bookings/"+booking+"/move-in/response";
        mvc.perform(post(path).with(token).contentType("application/json").content("{}").header("Idempotency-Key","test"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).with(token).contentType("application/json").content("{\"accepted\":true}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(agreements);
        when(agreements.respondToMoveIn(renter,booking,false,"test-key")).thenReturn(mock(Booking.class));
        mvc.perform(post(path).with(token).param("renterId",UUID.randomUUID().toString()).contentType("application/json")
                .content("{\"accepted\":false}").header("Idempotency-Key","test-key")).andExpect(status().isOk());
        verify(agreements).respondToMoveIn(renter,booking,false,"test-key");
    }
}
