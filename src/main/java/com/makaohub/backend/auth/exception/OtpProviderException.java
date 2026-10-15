package com.makaohub.backend.auth.exception;

public final class OtpProviderException extends RuntimeException {
    public enum Failure {
        CONFIGURATION("OTP_PROVIDER_CONFIGURATION", "Email verification could not send your code. Please contact support."),
        RATE_LIMITED("OTP_PROVIDER_RATE_LIMITED", "Email verification has reached a sending limit. Please wait before requesting another code."),
        UNAVAILABLE("OTP_PROVIDER_UNAVAILABLE", "Email verification is unavailable. If you requested a code, check your inbox before trying again.");

        private final String code;
        private final String detail;

        Failure(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }

        public String code() { return code; }
        public String detail() { return detail; }
    }

    private final Failure failure;

    /** Do not attach provider exceptions: their messages/bodies can contain PII. */
    public OtpProviderException(Failure failure) {
        super(failure.detail());
        this.failure = failure;
    }

    public Failure getFailure() { return failure; }
}
