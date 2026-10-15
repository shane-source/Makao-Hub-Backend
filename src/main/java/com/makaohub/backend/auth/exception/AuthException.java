package com.makaohub.backend.auth.exception;

public class AuthException extends RuntimeException {

    private final Reason reason;

    public AuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        INVALID_CREDENTIALS,
        INVALID_REFRESH_TOKEN,
        ACCOUNT_UNAVAILABLE,
        GOOGLE_REGISTRATION_REQUIRED,
        ROLE_SELECTION_REQUIRED,
        ROLE_SELECTION_NOT_REQUIRED,
        INVALID_ROLE_SELECTION,
        INVALID_OTP,
        OTP_RATE_LIMITED
    }
}
