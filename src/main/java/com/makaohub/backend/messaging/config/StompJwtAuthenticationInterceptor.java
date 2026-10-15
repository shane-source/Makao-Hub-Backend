package com.makaohub.backend.messaging.config;

import com.makaohub.backend.auth.domain.RoleName;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class StompJwtAuthenticationInterceptor
        implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtDecoder jwtDecoder;

    public StompJwtAuthenticationInterceptor(
            JwtDecoder jwtDecoder
    ) {
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    public Message<?> preSend(
            Message<?> message,
            MessageChannel channel
    ) {
        /*
         * Heartbeats carry no application data.
         */
        if (SimpMessageHeaderAccessor.getMessageType(
                message.getHeaders()
        ) == SimpMessageType.HEARTBEAT) {
            return message;
        }

        /*
         * Use Spring's original accessor so its user-change
         * callback runs and the session retains authentication.
         */
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(
                        message,
                        StompHeaderAccessor.class
                );

        if (accessor == null || accessor.getCommand() == null) {
            throw invalidToken();
        }

        StompCommand command = accessor.getCommand();

        if (command == StompCommand.CONNECT) {
            List<String> values = accessor.getNativeHeader(
                    HttpHeaders.AUTHORIZATION
            );

            /*
             * Reject missing or duplicate Authorization headers.
             */
            if (values == null || values.size() != 1) {
                throw invalidToken();
            }

            String authorization = values.getFirst();

            if (authorization == null
                    || !authorization.startsWith(BEARER_PREFIX)) {
                throw invalidToken();
            }

            String token = authorization
                    .substring(BEARER_PREFIX.length())
                    .trim();

            if (token.isEmpty()) {
                throw invalidToken();
            }

            accessor.setUser(authenticate(token));

            return message;
        }

        /*
         * Subsequent frames must belong to an authenticated
         * JWT session established during CONNECT.
         */
        if (!(accessor.getUser()
                instanceof JwtAuthenticationToken authentication)
                || !authentication.isAuthenticated()) {
            throw invalidToken();
        }

        /*
         * Allow cleanup after token expiry.
         * These operations do not disclose application data.
         */
        if (command == StompCommand.DISCONNECT
                || command == StompCommand.UNSUBSCRIBE) {
            return message;
        }

        /*
         * Revalidate the original session credential.
         * A new Authorization header on SEND cannot replace it.
         */
        JwtAuthenticationToken verified = authenticate(
                authentication.getToken().getTokenValue()
        );

        if (!verified.getName().equals(authentication.getName())) {
            throw invalidToken();
        }

        /*
         * The next interceptor still checks whether the requested
         * destination is allowed.
         */
        return message;
    }

    /**
     * Validates the JWT and constructs its authenticated identity.
     *
     * The configured decoder also checks issuer, audience,
     * expiration and current account authorization.
     */
    private JwtAuthenticationToken authenticate(
            String token
    ) {
        try {
            Jwt jwt = jwtDecoder.decode(token);

            UUID.fromString(jwt.getSubject());

            List<String> roles =
                    jwt.getClaimAsStringList("roles");

            if (!"access".equals(
                    jwt.getClaimAsString("token_type")
            ) || roles == null || roles.size() != 1) {
                throw invalidToken();
            }

            RoleName role = RoleName.valueOf(
                    roles.getFirst()
            );

            return new JwtAuthenticationToken(
                    jwt,
                    List.of(
                            new SimpleGrantedAuthority(
                                    "ROLE_" + role.name()
                            )
                    ),
                    jwt.getSubject()
            );
        } catch (
                JwtException
                | IllegalArgumentException
                | NullPointerException exception
        ) {
            throw invalidToken();
        }
    }

    /**
     * Returns a generic authentication failure without including
     * token contents or private account details.
     */
    private static BadCredentialsException invalidToken() {
        return new BadCredentialsException(
                "A valid Bearer access token is required. "
                        + "Reconnect after signing in."
        );
    }
}