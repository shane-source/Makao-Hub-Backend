package com.makaohub.backend.messaging.domain;

/**
 * Lifecycle state of a free roommate chat thread.
 */
public enum RoommateChatThreadStatus {

    /**
     * Both authorized participants may send and read messages.
     */
    OPEN,

    /**
     * The conversation is closed and no new messages or presence
     * information may be accessed.
     */
    CLOSED
}