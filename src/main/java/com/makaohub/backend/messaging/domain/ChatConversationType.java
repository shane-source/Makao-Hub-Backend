package com.makaohub.backend.messaging.domain;

/**
 * Identifies the business event that created a chat conversation.
 *
 * The value is stored by the backend and must never be accepted as
 * authoritative client input.
 */
public enum ChatConversationType {

    /**
     * Conversation between a Renter and Landlord.
     *
     * It becomes available after the Renter submits a rental
     * application for the Landlord's listing.
     */
    RENTAL_APPLICATION,

    /**
     * Conversation between a Renter and Sublessor.
     *
     * It becomes available after the Sublessor accepts the Renter's
     * roommate interest.
     */
    ROOMMATE_INTEREST
}