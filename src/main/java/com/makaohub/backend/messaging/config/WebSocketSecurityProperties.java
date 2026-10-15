package com.makaohub.backend.messaging.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties(
        prefix = "security.websocket"
)
public record WebSocketSecurityProperties(

        /*
         * Exact website origins allowed to perform the WebSocket
         * handshake.
         */
        @NotEmpty
        List<String> allowedOrigins,

        /*
         * Maximum complete inbound STOMP message size.
         *
         * Minimum: 1 KiB
         * Maximum: 64 KiB
         */
        @Min(1_024)
        @Max(65_536)
        int inboundMessageSizeBytes,

        /*
         * Per-account chat message rate.
         *
         * The hard maximum prevents an unsafe environment override.
         */
        @Min(1)
        @Max(300)
        long messagesPerMinute,

        /*
         * RECOMMENDED default: an outbound send may occupy the
         * transport for at most ten seconds.
         */
        @DefaultValue("10000")
        @Min(1_000)
        @Max(60_000)
        int sendTimeLimitMs,

        /*
         * RECOMMENDED default: at most 512 KiB may be buffered for
         * one slow WebSocket session.
         */
        @DefaultValue("524288")
        @Min(8_192)
        @Max(1_048_576)
        int sendBufferSizeLimitBytes,

        /*
         * RECOMMENDED default: a newly opened socket must send its
         * first STOMP frame within thirty seconds.
         */
        @DefaultValue("30000")
        @Min(1_000)
        @Max(120_000)
        int timeToFirstMessageMs
) {

    /**
     * Defensively copies and validates the browser-origin allowlist.
     */
    public WebSocketSecurityProperties {
        if (allowedOrigins == null) {
            throw new IllegalArgumentException(
                    "WebSocket allowed origins are required."
            );
        }

        for (String origin : allowedOrigins) {
            /*
             * Wildcards would allow untrusted websites to initiate
             * authenticated WebSocket connections from a browser.
             */
            if (origin == null
                    || origin.isBlank()
                    || origin.contains("*")) {
                throw new IllegalArgumentException(
                        "WebSocket origins must be explicit values."
                );
            }
        }

        /*
         * Prevents another component from modifying the allowlist
         * after configuration validation.
         */
        allowedOrigins =
                List.copyOf(allowedOrigins);
    }
}