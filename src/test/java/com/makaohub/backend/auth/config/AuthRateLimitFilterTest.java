package com.makaohub.backend.auth.config;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRateLimitFilterTest {

    private static final String CLIENT_IP = "203.0.113.10";

    private final AuthRateLimitFilter filter =
            new AuthRateLimitFilter();

    @Test
    void enforcesIndependentLimitForEveryAuthEndpoint()
            throws Exception {
        Map<String, Integer> endpointCapacities = Map.of(
                "/api/v1/auth/login", 10,
                "/api/v1/auth/register", 5,
                "/api/v1/auth/google", 10,
                "/api/v1/auth/google/register", 5,
                "/api/v1/auth/refresh", 30,
                "/api/v1/auth/logout", 30
        );

        for (Map.Entry<String, Integer> endpoint
                : endpointCapacities.entrySet()) {
            for (int requestNumber = 0;
                 requestNumber < endpoint.getValue();
                 requestNumber++) {
                MockHttpServletResponse allowed =
                        sendPost(endpoint.getKey());

                assertEquals(
                        HttpStatus.OK.value(),
                        allowed.getStatus(),
                        endpoint.getKey()
                );
            }

            MockHttpServletResponse blocked =
                    sendPost(endpoint.getKey());

            assertEquals(
                    HttpStatus.TOO_MANY_REQUESTS.value(),
                    blocked.getStatus(),
                    endpoint.getKey()
            );

            assertNotNull(
                    blocked.getHeader("Retry-After")
            );

            assertTrue(
                    blocked.getContentAsString()
                            .contains("RATE_LIMIT_EXCEEDED")
            );
        }
    }

    private MockHttpServletResponse sendPost(String path)
            throws ServletException, IOException {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", path);

        request.setRemoteAddr(CLIENT_IP);

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                new MockFilterChain()
        );

        return response;
    }
}