package com.makaohub.backend.payment.entitlement.domain;

/** Lifecycle of a renter's paid listing entitlement. */
public enum ListingUnlockStatus {
    ACTIVE,
    REVOKED,
    REFUNDED
}
