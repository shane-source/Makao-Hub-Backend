package com.makaohub.backend.messaging.domain;

/**
 * Lifecycle state of a free housing conversation.
 *
 * There is deliberately no LOCKED status. Access is determined by
 * the qualifying rental application or accepted roommate interest,
 * together with account, role, verification and ownership checks.
 */
public enum ChatThreadStatus {

    /**
     * Both authorized participants may read and send messages.
     */
    OPEN,

    /**
     * The conversation remains available for permitted history access,
     * but new messages cannot be sent.
     */
    CLOSED
}