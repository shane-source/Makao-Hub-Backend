package com.makaohub.backend.admin;

public enum StaffRole {
    STAFF_LEAD, VERIFICATION_REVIEWER, SAFETY_MODERATOR, PAYMENT_REVIEWER;

    public enum Permission { STAFF, ASSIGN_VERIFICATION, VERIFICATION, LISTING_MODERATION, SAFETY, SAFETY_VIEW, PAYMENTS, PAYMENTS_VIEW, TEST_RELEASE, AUDIT }

    public boolean permits(Permission permission) {
        return switch (permission) {
            case STAFF, ASSIGN_VERIFICATION -> this == STAFF_LEAD;
            case VERIFICATION -> this == STAFF_LEAD || this == VERIFICATION_REVIEWER;
            case LISTING_MODERATION -> this == STAFF_LEAD || this == SAFETY_MODERATOR;
            case SAFETY -> this == SAFETY_MODERATOR;
            case SAFETY_VIEW -> this == STAFF_LEAD || this == SAFETY_MODERATOR;
            case PAYMENTS -> this == PAYMENT_REVIEWER;
            case PAYMENTS_VIEW -> this == STAFF_LEAD || this == PAYMENT_REVIEWER;
            case TEST_RELEASE -> this == STAFF_LEAD || this == PAYMENT_REVIEWER;
            case AUDIT -> this == STAFF_LEAD;
        };
    }
}
