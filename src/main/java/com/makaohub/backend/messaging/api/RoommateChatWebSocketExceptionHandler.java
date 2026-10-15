package com.makaohub.backend.messaging.api;

import com.makaohub.backend.auth.exception.AuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@ControllerAdvice(
        assignableTypes = {
                RoommateChatWebSocketController.class,
                RentalChatWebSocketController.class
        }
)
public class RoommateChatWebSocketExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    SendRentalChatMessageRequest.RoommateChatWebSocketExceptionHandler.class
            );

    private static final String ERROR_DESTINATION =
            "/queue/chat.errors";

    private static final int MAX_CAUSE_DEPTH = 10;

    /*
     * Send the sanitized error only to the session that submitted
     * the failed request, not the user's other connected devices.
     */
    @MessageExceptionHandler(Exception.class)
    @SendToUser(
            destinations = ERROR_DESTINATION,
            broadcast = false
    )
    public RoommateChatErrorResponse handle(Exception exception) {
        Throwable relevant = findRelevantCause(exception);

        if (relevant instanceof MethodArgumentNotValidException
                || relevant instanceof MessageConversionException
                || relevant instanceof IllegalArgumentException) {
            return error(
                    RoommateChatErrorResponse.Code.INVALID_REQUEST,
                    "The chat request is invalid."
            );
        }

        if (relevant instanceof BadCredentialsException
                || relevant instanceof AuthException) {
            return error(
                    RoommateChatErrorResponse.Code.AUTHENTICATION_REQUIRED,
                    "Reconnect after signing in."
            );
        }

        if (relevant instanceof AccessDeniedException) {
            return error(
                    RoommateChatErrorResponse.Code.ACCESS_DENIED,
                    "You are not allowed to perform this chat operation."
            );
        }

        if (relevant instanceof ResponseStatusException statusException) {
            return fromStatus(statusException);
        }

        logUnexpected(relevant);

        return error(
                RoommateChatErrorResponse.Code.INTERNAL_ERROR,
                "The chat operation could not be completed."
        );
    }

    /*
     * Translate service failures into stable client-facing codes.
     * Never return exception reasons or original exception messages.
     */
    private static RoommateChatErrorResponse fromStatus(
            ResponseStatusException exception
    ) {
        int status = exception.getStatusCode().value();

        if (status == HttpStatus.BAD_REQUEST.value()) {
            return error(
                    RoommateChatErrorResponse.Code.INVALID_REQUEST,
                    "The chat request is invalid."
            );
        }

        if (status == HttpStatus.UNAUTHORIZED.value()) {
            return error(
                    RoommateChatErrorResponse.Code.AUTHENTICATION_REQUIRED,
                    "Reconnect after signing in."
            );
        }

        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return error(
                    RoommateChatErrorResponse.Code.RATE_LIMITED,
                    "Too many chat messages. Try again shortly."
            );
        }

        /*
         * Do not distinguish another user's conversation from a
         * missing conversation or an unavailable conversation state.
         */
        if (status == HttpStatus.FORBIDDEN.value()
                || status == HttpStatus.NOT_FOUND.value()
                || status == HttpStatus.CONFLICT.value()) {
            return error(
                    RoommateChatErrorResponse.Code.CHAT_UNAVAILABLE,
                    "Chat is not currently available."
            );
        }

        logUnexpected(exception);

        return error(
                RoommateChatErrorResponse.Code.INTERNAL_ERROR,
                "The chat operation could not be completed."
        );
    }

    /*
     * Spring may wrap a service exception in a messaging exception.
     * Inspect at most ten causes so traversal always terminates.
     */
    private static Throwable findRelevantCause(Throwable failure) {
        Throwable current = failure;

        for (int depth = 0;
             current != null && depth < MAX_CAUSE_DEPTH;
             depth++) {

            if (isKnownFailure(current)) {
                return current;
            }

            Throwable cause = current.getCause();

            if (cause == current) {
                break;
            }

            current = cause;
        }

        return failure;
    }

    private static boolean isKnownFailure(Throwable failure) {
        return failure instanceof MethodArgumentNotValidException
                || failure instanceof MessageConversionException
                || failure instanceof IllegalArgumentException
                || failure instanceof BadCredentialsException
                || failure instanceof AuthException
                || failure instanceof AccessDeniedException
                || failure instanceof ResponseStatusException;
    }

    private static RoommateChatErrorResponse error(
            RoommateChatErrorResponse.Code code,
            String message
    ) {
        return new RoommateChatErrorResponse(
                code,
                message,
                Instant.now()
        );
    }

    /*
     * Exception messages and stack traces can contain submitted
     * content or private identifiers. Log only the exception class.
     */
    private static void logUnexpected(Throwable failure) {
        LOGGER.error(
                "Unexpected chat WebSocket failure. type={}",
                failure.getClass().getName()
        );
    }
}