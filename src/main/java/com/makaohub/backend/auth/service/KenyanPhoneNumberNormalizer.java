package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.exception.RegistrationException;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KenyanPhoneNumberNormalizer {

    private static final Pattern KENYAN_MOBILE_PATTERN =
            Pattern.compile("^(?:\\+254|254|0)([17]\\d{8})$");

    private KenyanPhoneNumberNormalizer() {
    }

    public static String normalize(String phoneNumber) {
        String candidate = Objects.requireNonNull(phoneNumber)
                .strip();

        Matcher matcher = KENYAN_MOBILE_PATTERN.matcher(candidate);

        if (!matcher.matches()) {
            throw new RegistrationException(
                    RegistrationException.Reason.INVALID_PHONE_NUMBER,
                    "Phone number must be a valid Kenyan mobile number."
            );
        }

        return "+254" + matcher.group(1);
    }
}