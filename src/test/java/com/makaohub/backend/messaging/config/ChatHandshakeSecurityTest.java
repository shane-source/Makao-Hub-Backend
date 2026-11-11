package com.makaohub.backend.messaging.config;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Proves the HTTP handshake can reach STOMP authentication without opening other routes. */
@WebMvcTest(ChatHandshakeSecurityTest.HandshakeProbe.class)
@Import({SecurityConfig.class, ChatHandshakeSecurityTest.HandshakeProbe.class})
class ChatHandshakeSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean UploadSecurityProperties uploadSecurityProperties;

    @Test void onlyTheHandshakeGetBypassesHttpBearerAuthentication() throws Exception {
        mvc.perform(get("/ws")).andExpect(status().isOk());
        mvc.perform(post("/ws")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ws/other")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/renter/probe")).andExpect(status().isUnauthorized());
    }

    @RestController
    public static class HandshakeProbe {
        @GetMapping("/ws") public String handshake() { return "handshake"; }
    }
}
