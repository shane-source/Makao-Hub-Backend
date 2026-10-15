package com.makaohub.backend.messaging.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
public class RoommateWebSocketConfig
        implements WebSocketMessageBrokerConfigurer {

    /*
     * RECOMMENDED heartbeat starting values.
     *
     * The server and each STOMP client negotiate the actual heartbeat
     * behavior during CONNECT.
     */
    private static final long SERVER_HEARTBEAT_MS =
            10_000L;

    private static final long CLIENT_HEARTBEAT_MS =
            10_000L;

    private final WebSocketSecurityProperties properties;

    private final StompJwtAuthenticationInterceptor
            authenticationInterceptor;

    private final StompDestinationAuthorizationInterceptor
            authorizationInterceptor;

    private final SanitizedStompErrorHandler
            errorHandler;

    public RoommateWebSocketConfig(
            WebSocketSecurityProperties properties,
            StompJwtAuthenticationInterceptor
                    authenticationInterceptor,
            StompDestinationAuthorizationInterceptor
                    authorizationInterceptor,
            SanitizedStompErrorHandler errorHandler
    ) {
        this.properties = properties;
        this.authenticationInterceptor =
                authenticationInterceptor;
        this.authorizationInterceptor =
                authorizationInterceptor;
        this.errorHandler = errorHandler;
    }

    /**
     * Creates a dedicated scheduler for WebSocket broker heartbeats.
     *
     * Presence cleanup uses a different scheduler, preventing cleanup
     * work from delaying heartbeat frames.
     */
    @Bean
    public ThreadPoolTaskScheduler
    roommateHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(1);

        scheduler.setThreadNamePrefix(
                "roommate-heartbeat-"
        );

        scheduler.setRemoveOnCancelPolicy(true);

        return scheduler;
    }

    /**
     * Registers the WebSocket handshake endpoint, origin allowlist
     * and sanitized protocol error handler.
     */
    @Override
    public void registerStompEndpoints(
            StompEndpointRegistry registry
    ) {
        registry.setErrorHandler(
                errorHandler
        );

        registry.addEndpoint("/ws")
                .setAllowedOrigins(
                        properties
                                .allowedOrigins()
                                .toArray(String[]::new)
                );
    }

    /**
     * Configures application, private-user and broker destinations.
     */
    @Override
    public void configureMessageBroker(
            MessageBrokerRegistry registry
    ) {
        /*
         * Client SEND frames targeting /app are routed to
         * @MessageMapping controller methods.
         */
        registry.setApplicationDestinationPrefixes(
                "/app"
        );

        /*
         * /user destinations are translated into private,
         * session-specific queues.
         */
        registry.setUserDestinationPrefix(
                "/user"
        );

        /*
         * Only private queues are enabled. There is no public /topic
         * prefix.
         */
        registry.enableSimpleBroker("/queue")
                .setTaskScheduler(
                        roommateHeartbeatScheduler()
                )
                .setHeartbeatValue(
                        new long[] {
                                SERVER_HEARTBEAT_MS,
                                CLIENT_HEARTBEAT_MS
                        }
                );
    }

    /**
     * Authenticates every applicable inbound frame before checking
     * whether its destination is allowed.
     */
    @Override
    public void configureClientInboundChannel(
            ChannelRegistration registration
    ) {
        registration.interceptors(
                authenticationInterceptor,
                authorizationInterceptor
        );
    }

    /**
     * Applies inbound and outbound transport limits to every
     * WebSocket session.
     */
    @Override
    public void configureWebSocketTransport(
            WebSocketTransportRegistration registration
    ) {
        registration
                /*
                 * Rejects oversized inbound STOMP messages before they
                 * reach application controllers.
                 */
                .setMessageSizeLimit(
                        properties
                                .inboundMessageSizeBytes()
                )

                /*
                 * Stops a slow or blocked client from occupying an
                 * outbound sender indefinitely.
                 */
                .setSendTimeLimit(
                        properties
                                .sendTimeLimitMs()
                )

                /*
                 * Bounds unsent outbound data retained for one slow
                 * client.
                 */
                .setSendBufferSizeLimit(
                        properties
                                .sendBufferSizeLimitBytes()
                )

                /*
                 * Closes idle connections that complete the WebSocket
                 * handshake but never send a STOMP frame.
                 */
                .setTimeToFirstMessage(
                        properties
                                .timeToFirstMessageMs()
                );
    }
}