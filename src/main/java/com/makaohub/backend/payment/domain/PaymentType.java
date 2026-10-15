package com.makaohub.backend.payment.domain;

/**
 * Product or booking action that created a payment intent.
 */
public enum PaymentType {
    BOOKING,
    EXACT_LOCATION_UNLOCK,
    VIDEO_UNLOCK,
    PHONE_UNLOCK,
    PLAN_UPGRADE,
    BOOST,
    LISTING_ADD_ON
}
