package com.makaohub.backend.messaging.service;

import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChatContactMaskingService {

    private static final String MASK = "[contact hidden]";

    /*
     * Detects ordinary email addresses without capturing surrounding
     * punctuation.
     *
     * Examples:
     * amina@example.com
     * amina.wanjiku+housing@example.co.ke
     */
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![a-z0-9._%+-])"
                    + "[a-z0-9._%+-]+"
                    + "@"
                    + "[a-z0-9.-]+"
                    + "\\.[a-z]{2,}"
                    + "(?![a-z0-9.-])"
    );

    /*
     * Detects common Kenyan mobile-number formats.
     *
     * Examples:
     * +254712345678
     * +254 712 345 678
     * +254-712-345-678
     * 254712345678
     * 0712345678
     * 0712 345 678
     * 0712-345-678
     * 712345678
     *
     * Only optional spaces and hyphens are accepted as separators.
     * This keeps the expression narrow and reduces false positives.
     */
    private static final Pattern KENYAN_PHONE = Pattern.compile(
            "(?<!\\d)"
                    + "(?:(?:\\+?254|0)[ -]?)?"
                    + "[17]\\d{2}"
                    + "(?:[ -]?\\d{3}){2}"
                    + "(?!\\d)"
    );

    /*
     * Detects HTTP, HTTPS and www links.
     */
    private static final Pattern WEB_LINK = Pattern.compile(
            "(?i)\\b(?:https?://|www\\.)[^\\s]{3,}"
    );

    /*
     * Detects common social-media handles such as @amina_wanjiku.
     *
     * Email addresses are masked before this pattern runs.
     */
    private static final Pattern SOCIAL_HANDLE = Pattern.compile(
            "(?<![\\w@])@[a-zA-Z0-9_]{2,30}\\b"
    );

    /**
     * Masks contact-sharing attempts and returns safe moderation
     * information.
     *
     * The original contact information is not included in the returned
     * flag codes. Only safe labels such as "phone" and "email" are
     * returned.
     */
    public MaskedMessage mask(
            String originalContent
    ) {
        String masked = originalContent;

        EnumSet<ContactFlag> flags =
                EnumSet.noneOf(ContactFlag.class);

        masked = replace(
                masked,
                EMAIL,
                ContactFlag.EMAIL,
                flags
        );

        masked = replace(
                masked,
                KENYAN_PHONE,
                ContactFlag.PHONE,
                flags
        );

        masked = replace(
                masked,
                WEB_LINK,
                ContactFlag.WEB_LINK,
                flags
        );

        masked = replace(
                masked,
                SOCIAL_HANDLE,
                ContactFlag.SOCIAL_HANDLE,
                flags
        );

        String flagCodes = flags.isEmpty()
                ? null
                : flags.stream()
                .map(flag ->
                        flag.name()
                                .toLowerCase(Locale.ROOT)
                )
                .collect(Collectors.joining(","));

        return new MaskedMessage(
                masked,
                !flags.isEmpty(),
                flagCodes
        );
    }

    /**
     * Replaces every match with the safe placeholder.
     *
     * The detected value is never returned or logged.
     */
    private static String replace(
            String input,
            Pattern pattern,
            ContactFlag flag,
            EnumSet<ContactFlag> flags
    ) {
        var matcher = pattern.matcher(input);

        if (!matcher.find()) {
            return input;
        }

        flags.add(flag);

        return matcher.replaceAll(MASK);
    }

    /**
     * Safe internal moderation categories.
     *
     * These values contain no personal information.
     */
    private enum ContactFlag {
        EMAIL,
        PHONE,
        WEB_LINK,
        SOCIAL_HANDLE
    }

    /**
     * Result returned to the message service.
     *
     * content contains only the masked message.
     * contactMasked tells the chat client to display a flagged label.
     * flagCodes contains safe categories, never detected values.
     */
    public record MaskedMessage(
            String content,
            boolean contactMasked,
            String flagCodes
    ) {
    }
}