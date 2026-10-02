package com.makaohub.backend.auth.exception;

public class RegistrationException extends RuntimeException {

    private final Reason reason;

    public RegistrationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        EMAIL_ALREADY_REGISTERED,
        PHONE_ALREADY_REGISTERED,
        ADMIN_ROLE_NOT_ALLOWED,
        INVALID_PHONE_NUMBER,
        PASSWORD_TOO_LONG,
        ROLE_NOT_CONFIGURED
    }
}