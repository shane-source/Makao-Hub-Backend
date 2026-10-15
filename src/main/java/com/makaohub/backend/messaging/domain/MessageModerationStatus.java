package com.makaohub.backend.messaging.domain;

/**
 * Describes the moderation state of a stored chat message.
 *
 * The enum values must exactly match the values allowed by the
 * roommate_chat_messages_moderation_status_check database constraint.
 */
public enum MessageModerationStatus {

    /**
     * The message passed the contact-information masking checks.
     */
    CLEAN,

    /**
     * Contact-sharing or another prohibited pattern was detected.
     *
     * The stored message body is still masked. FLAGGED means the
     * message should be available to the future Admin moderation
     * queue.
     */
    FLAGGED
}