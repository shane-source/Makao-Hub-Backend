package com.makaohub.backend.payment.domain;

/**
 * Lifecycle of a payment intent before and after provider settlement.
 */
public enum PaymentStatus {
    CREATED,
    PENDING,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    REFUND_PENDING,
    REFUNDED,
    DISPUTED
}
