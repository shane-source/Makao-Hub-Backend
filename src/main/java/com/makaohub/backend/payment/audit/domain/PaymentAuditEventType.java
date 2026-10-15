package com.makaohub.backend.payment.audit.domain;

/** Immutable payment lifecycle facts written to the audit stream. */
public enum PaymentAuditEventType {
    INTENT_CREATED,
    PROVIDER_SUCCEEDED,
    PROVIDER_FAILED,
    BOOKING_PAID_HELD,
    LEDGER_POSTED,
    RENTER_AGREEMENT_CONFIRMED,
    OWNER_AGREEMENT_CONFIRMED,
    MOVE_IN_CONFIRMED,
    PAYMENT_DISPUTED,
    ESCROW_RELEASED
}
