package com.makaohub.backend.admin;

import org.junit.jupiter.api.Test;
import static com.makaohub.backend.admin.StaffRole.*;
import static com.makaohub.backend.admin.StaffRole.Permission.*;
import static org.junit.jupiter.api.Assertions.*;

class StaffRoleTest {
    @Test void leadCanApproveListingsVerificationAndTestReleasesWithoutLiveFinancialOrSafetyActions() {
        assertTrue(STAFF_LEAD.permits(SAFETY_VIEW));
        assertTrue(STAFF_LEAD.permits(PAYMENTS_VIEW));
        assertTrue(STAFF_LEAD.permits(STAFF));
        assertTrue(STAFF_LEAD.permits(ASSIGN_VERIFICATION));
        assertTrue(STAFF_LEAD.permits(AUDIT));
        assertTrue(STAFF_LEAD.permits(LISTING_MODERATION));
        assertTrue(STAFF_LEAD.permits(VERIFICATION));
        assertFalse(STAFF_LEAD.permits(SAFETY));
        assertFalse(STAFF_LEAD.permits(PAYMENTS));
        assertTrue(STAFF_LEAD.permits(TEST_RELEASE));
        assertTrue(SAFETY_MODERATOR.permits(LISTING_MODERATION));
        assertTrue(SAFETY_MODERATOR.permits(SAFETY_VIEW));
        assertTrue(SAFETY_MODERATOR.permits(SAFETY));
        assertTrue(PAYMENT_REVIEWER.permits(PAYMENTS_VIEW));
        assertTrue(PAYMENT_REVIEWER.permits(TEST_RELEASE));
        assertFalse(SAFETY_MODERATOR.permits(TEST_RELEASE));
        assertFalse(VERIFICATION_REVIEWER.permits(TEST_RELEASE));
        assertFalse(SAFETY_MODERATOR.permits(PAYMENTS_VIEW));
        assertFalse(PAYMENT_REVIEWER.permits(SAFETY_VIEW));
        assertFalse(PAYMENT_REVIEWER.permits(LISTING_MODERATION));
        assertFalse(VERIFICATION_REVIEWER.permits(SAFETY_VIEW));
        assertFalse(VERIFICATION_REVIEWER.permits(LISTING_MODERATION));
        assertFalse(VERIFICATION_REVIEWER.permits(PAYMENTS_VIEW));
    }
}
