package com.makaohub.backend.messaging.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.nio.charset.StandardCharsets;

@Component
public class SanitizedStompErrorHandler
        extends StompSubProtocolErrorHandler {

    /*
     * Bounds exception-cause traversal and protects against malformed
     * or circular cause chains.
     */
    private static final int MAX_CAUSE_DEPTH = 10;

    private static final String AUTHENTICATION_ERROR =
            "WebSocket authentication failed. "
                    + "Reconnect after signing in.";

    private static final String ACCESS_ERROR =
            "WebSocket operation is not permitted.";

    private static final String GENERIC_ERROR =
            "WebSocket request could not be processed.";

    /**
     * Handles failures raised while processing a client STOMP frame.
     *
     * The original client message is intentionally not copied into
     * the response because it could contain an Authorization header,
     * login, passcode, destination or private message content.
     */
    @Override
    public Message<byte[]> handleClientMessageProcessingError(
            Message<byte[]> clientMessage,
            Throwable exception
    ) {
        if (containsCause(
                exception,
                AuthenticationException.class
        )) {
            return errorFrame(
                    AUTHENTICATION_ERROR
            );
        }

        if (containsCause(
                exception,
                AccessDeniedException.class
        )) {
            return errorFrame(
                    ACCESS_ERROR
            );
        }

        return errorFrame(
                GENERIC_ERROR
        );
    }

    /**
     * Sanitizes ERROR messages produced by the broker itself.
     *
     * The original error payload and headers are discarded.
     */
    @Override
    public Message<byte[]> handleErrorMessageToClient(
            Message<byte[]> errorMessage
    ) {
        return errorFrame(
                GENERIC_ERROR
        );
    }

    /**
     * Searches a bounded exception-cause chain for a known safe
     * classification.
     *
     * Complexity is O(1) because traversal is capped at ten entries.
     */
    private static boolean containsCause(
            Throwable failure,
            Class<? extends Throwable> expectedType
    ) {
        Throwable current = failure;

        for (
                int depth = 0;
                current != null
                        && depth < MAX_CAUSE_DEPTH;
                depth++
        ) {
            if (expectedType.isInstance(current)) {
                return true;
            }

            /*
             * Protects against a self-referencing exception cause.
             */
            if (current.getCause() == current) {
                break;
            }

            current = current.getCause();
        }

        return false;
    }

    /**
     * Constructs a completely new STOMP ERROR frame containing only
     * server-controlled text.
     */
    private static Message<byte[]> errorFrame(
            String safeMessage
    ) {
        byte[] payload =
                safeMessage.getBytes(
                        StandardCharsets.UTF_8
                );

        StompHeaderAccessor headers =
                StompHeaderAccessor.create(
                        StompCommand.ERROR
                );

        /*
         * Both the STOMP message header and body contain only the
         * fixed safe message.
         */
        headers.setMessage(safeMessage);

        headers.setContentType(
                new MimeType(
                        "text",
                        "plain",
                        StandardCharsets.UTF_8
                )
        );

        headers.setContentLength(
                payload.length
        );

        return MessageBuilder.createMessage(
                payload,
                headers.getMessageHeaders()
        );
    }
}