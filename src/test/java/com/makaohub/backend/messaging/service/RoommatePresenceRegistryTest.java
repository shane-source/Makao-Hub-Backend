package com.makaohub.backend.messaging.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.messaging.SessionConnectedEvent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoommatePresenceRegistryTest {

    private static final Instant ISSUED_AT =
            Instant.parse("2026-10-11T08:00:00Z");

    @Test
    void purgeRemovesInvalidSessionsAndKeepsValidSessions() {
        UUID expiredUserId = UUID.randomUUID();
        UUID validUserId = UUID.randomUUID();

        List<Object> publishedEvents =
                new ArrayList<>();

        ApplicationEventPublisher eventPublisher =
                publishedEvents::add;

        Jwt validJwt = jwt(
                "valid-token",
                validUserId
        );

        /*
         * Simulates the production JwtDecoder.
         *
         * Production may reject a token because it is expired,
         * revoked, malformed, incorrectly signed, or has invalid
         * issuer, audience, or authorization-version claims.
         */
        JwtDecoder decoder = tokenValue -> {
            if ("valid-token".equals(tokenValue)) {
                return validJwt;
            }

            throw new JwtException("Token rejected");
        };

        RoommatePresenceRegistry registry =
                new RoommatePresenceRegistry(
                        decoder,
                        eventPublisher
                );

        connect(
                registry,
                "expired-session-one",
                jwt(
                        "expired-token-one",
                        expiredUserId
                )
        );

        connect(
                registry,
                "expired-session-two",
                jwt(
                        "expired-token-two",
                        expiredUserId
                )
        );

        connect(
                registry,
                "valid-session",
                validJwt
        );

        /*
         * Confirm that the synthetic connection events were accepted
         * before testing cleanup.
         */
        assertEquals(
                2,
                registry.connectionCount(expiredUserId)
        );

        assertEquals(
                1,
                registry.connectionCount(validUserId)
        );

        /*
         * Remove connection events so only cleanup-generated events
         * remain in the collector.
         */
        publishedEvents.clear();

        int removed =
                registry.purgeInvalidSessions();

        /*
         * Both rejected sessions must be removed.
         */
        assertEquals(2, removed);

        assertEquals(
                0,
                registry.connectionCount(expiredUserId)
        );

        assertFalse(
                registry.isOnline(expiredUserId)
        );

        /*
         * The valid session must remain registered and online.
         */
        assertEquals(
                1,
                registry.connectionCount(validUserId)
        );

        assertTrue(
                registry.isOnline(validUserId)
        );

        List<RoommatePresenceRegistry.PresenceChanged>
                presenceEvents =
                publishedEvents.stream()
                        .filter(
                                RoommatePresenceRegistry
                                        .PresenceChanged.class
                                        ::isInstance
                        )
                        .map(
                                RoommatePresenceRegistry
                                        .PresenceChanged.class
                                        ::cast
                        )
                        .toList();

        /*
         * Two removed sessions belonging to the same account must
         * produce only one presence-change event.
         */
        assertEquals(
                1,
                presenceEvents.size()
        );

        assertEquals(
                expiredUserId,
                presenceEvents.getFirst().userId()
        );
    }

    /**
     * Creates an authenticated Spring STOMP connection event and
     * submits it directly to the presence registry.
     */
    private static void connect(
            RoommatePresenceRegistry registry,
            String sessionId,
            Jwt jwt
    ) {
        /*
         * The two-argument constructor marks the token authenticated.
         *
         * The one-argument constructor leaves it unauthenticated in
         * Spring Security 7, causing the registry to reject it.
         */
        JwtAuthenticationToken authentication =
                new JwtAuthenticationToken(
                        jwt,
                        List.of()
                );

        SimpMessageHeaderAccessor headers =
                SimpMessageHeaderAccessor.create(
                        SimpMessageType.CONNECT_ACK
                );

        headers.setSessionId(sessionId);
        headers.setUser(authentication);

        Message<byte[]> message =
                MessageBuilder.createMessage(
                        new byte[0],
                        headers.getMessageHeaders()
                );

        registry.onConnected(
                new SessionConnectedEvent(
                        RoommatePresenceRegistryTest.class,
                        message,
                        authentication
                )
        );
    }

    /**
     * Builds the JWT originally attached to a WebSocket connection.
     */
    private static Jwt jwt(
            String tokenValue,
            UUID userId
    ) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject(userId.toString())
                .issuedAt(ISSUED_AT)
                .expiresAt(
                        ISSUED_AT.plusSeconds(900)
                )
                .build();
    }
}
