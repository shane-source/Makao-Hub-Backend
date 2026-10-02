package com.makaohub.backend.shared.error;

import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.exception.RegistrationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ProblemDetail> handleAuthException(
            AuthException exception
    ) {
        HttpStatus status = switch (exception.getReason()) {
            case INVALID_CREDENTIALS,
                 INVALID_REFRESH_TOKEN,
                 ACCOUNT_UNAVAILABLE -> HttpStatus.UNAUTHORIZED;

            case GOOGLE_REGISTRATION_REQUIRED ->
                    HttpStatus.CONFLICT;
        };

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                exception.getMessage()
        );

        problem.setTitle("Authentication failed");
        problem.setProperty(
                "code",
                exception.getReason().name()
        );

        return ResponseEntity
                .status(status)
                .body(problem);
    }

    @ExceptionHandler(RegistrationException.class)
    public ResponseEntity<ProblemDetail> handleRegistrationException(
            RegistrationException exception
    ) {
        HttpStatus status = switch (exception.getReason()) {
            case EMAIL_ALREADY_REGISTERED,
                 PHONE_ALREADY_REGISTERED -> HttpStatus.CONFLICT;

            case ADMIN_ROLE_NOT_ALLOWED,
                 INVALID_PHONE_NUMBER,
                 PASSWORD_TOO_LONG -> HttpStatus.BAD_REQUEST;

            case ROLE_NOT_CONFIGURED ->
                    HttpStatus.INTERNAL_SERVER_ERROR;
        };

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status,
                exception.getMessage()
        );

        problem.setTitle("Registration failed");
        problem.setProperty(
                "code",
                exception.getReason().name()
        );

        return ResponseEntity
                .status(status)
                .body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidationException(
            MethodArgumentNotValidException exception
    ) {
        Map<String, String> fieldErrors =
                new LinkedHashMap<>();

        exception.getBindingResult()
                .getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(
                        error.getField(),
                        error.getDefaultMessage()
                ));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request fields are invalid."
        );

        problem.setTitle("Validation failed");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("fields", fieldErrors);

        return ResponseEntity
                .badRequest()
                .body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableMessage() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The request body is missing or malformed."
        );

        problem.setTitle("Invalid request body");
        problem.setProperty(
                "code",
                "INVALID_REQUEST_BODY"
        );

        return ResponseEntity
                .badRequest()
                .body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataConflict() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The request conflicts with existing data."
        );

        problem.setTitle("Data conflict");
        problem.setProperty("code", "DATA_CONFLICT");

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(problem);
    }
}