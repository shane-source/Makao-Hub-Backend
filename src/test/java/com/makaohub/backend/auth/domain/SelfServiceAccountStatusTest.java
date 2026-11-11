package com.makaohub.backend.auth.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelfServiceAccountStatusTest {
    @Test
    void selfDeactivationCanReturnButDeletionReviewCannot() {
        User reversible = new User("Test User", "reversible@example.test", null, null);
        reversible.changeSelfServiceStatus(AccountStatus.DEACTIVATED);
        assertEquals(AccountStatus.DEACTIVATED, reversible.getAccountStatus());
        assertEquals(1L, reversible.getAuthorizationVersion());
        reversible.changeSelfServiceStatus(AccountStatus.ACTIVE);
        assertEquals(2L, reversible.getAuthorizationVersion());
        User deletion = new User("Test User", "deletion@example.test", null, null);
        deletion.changeSelfServiceStatus(AccountStatus.DELETION_PENDING);
        assertThrows(IllegalStateException.class, () -> deletion.changeSelfServiceStatus(AccountStatus.ACTIVE));
    }
}
