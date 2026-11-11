package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.domain.*;
import com.makaohub.backend.auth.exception.*;
import com.makaohub.backend.auth.repository.*;
import com.makaohub.backend.auth.service.PhoneProtectionService;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhoneCompletionServiceTest {
    private final UUID id = UUID.randomUUID();
    private final UserRepository users = mock(UserRepository.class);
    private final UserRoleRepository roles = mock(UserRoleRepository.class);
    private final PhoneProtectionService phones = mock(PhoneProtectionService.class);
    private final SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
    private final RoleVerificationRepository verification = mock(RoleVerificationRepository.class);
    private final ProfileService service = new ProfileService(users, roles, phones, storage, verification);
    private final User user = new User("Synthetic User", "synthetic@example.invalid", null, null);

    private void account(RoleName name) {
        Role role = mock(Role.class); when(role.getName()).thenReturn(name);
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(user));
        when(roles.findAllByUser_Id(id)).thenReturn(List.of(new UserRole(user, role, null)));
    }
    private void phone() {
        when(phones.protect("+254712345678")).thenReturn(new PhoneProtectionService.ProtectedPhone(new byte[]{1, 2}, "synthetic-hash"));
    }

    @Test void allPublicRolesCanFillOnlyTheirOwnMissingPhoneWithoutChangingVerification() {
        for (RoleName role : List.of(RoleName.RENTER, RoleName.LANDLORD, RoleName.SUBLESSOR)) {
            var fixture = new PhoneCompletionServiceTest(); fixture.account(role); fixture.phone();
            fixture.service.completeMissingPhone(fixture.id, "0712345678");
            assertArrayEquals(new byte[]{1, 2}, fixture.user.getPhoneCiphertext());
            assertEquals("synthetic-hash", fixture.user.getPhoneLookupHash());
            verify(fixture.users).findByIdForUpdate(fixture.id);
            verify(fixture.users).flush();
            verifyNoInteractions(fixture.storage, fixture.verification);
        }
    }

    @Test void repeatingSameCompletionIsIdempotentButCannotOverwritePhone() {
        account(RoleName.RENTER); phone();
        service.completeMissingPhone(id, "0712345678");
        service.completeMissingPhone(id, "+254712345678");
        verify(users, times(1)).flush();
        when(phones.protect("+254712345679")).thenReturn(new PhoneProtectionService.ProtectedPhone(new byte[]{3}, "different-hash"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.completeMissingPhone(id, "0712345679")).getStatusCode().value());
        assertEquals("synthetic-hash", user.getPhoneLookupHash());
        assertArrayEquals(new byte[]{1, 2}, user.getPhoneCiphertext());
    }

    @Test void duplicateAndInvalidPhonesLeaveAccountUnchanged() {
        account(RoleName.LANDLORD); phone();
        when(users.existsByPhoneLookupHash("synthetic-hash")).thenReturn(true);
        assertEquals(RegistrationException.Reason.PHONE_ALREADY_REGISTERED,
                assertThrows(RegistrationException.class, () -> service.completeMissingPhone(id, "0712345678")).getReason());
        for (String invalid : new String[]{null, "", " ", "invalid"}) {
            assertEquals(RegistrationException.Reason.INVALID_PHONE_NUMBER,
                    assertThrows(RegistrationException.class, () -> service.completeMissingPhone(id, invalid)).getReason());
        }
        assertNull(user.getPhoneCiphertext()); assertNull(user.getPhoneLookupHash());
        verify(users, never()).flush();
    }

    @Test void missingInactiveAndNonPublicRoleAccountsCannotCompletePhones() {
        assertThrows(AuthException.class, () -> service.completeMissingPhone(id, "0712345678"));
        User inactive = mock(User.class); when(inactive.getAccountStatus()).thenReturn(AccountStatus.SUSPENDED);
        when(users.findByIdForUpdate(id)).thenReturn(Optional.of(inactive));
        assertThrows(AuthException.class, () -> service.completeMissingPhone(id, "0712345678"));
        account(RoleName.ADMIN);
        assertThrows(AuthException.class, () -> service.completeMissingPhone(id, "0712345678"));
        when(roles.findAllByUser_Id(id)).thenReturn(List.of());
        assertThrows(AuthException.class, () -> service.completeMissingPhone(id, "0712345678"));
        verifyNoInteractions(phones, storage, verification);
        verify(users, never()).flush();
    }

    @Test void domainProtectsExistingPhoneAndCannotPartiallyFillIt() {
        assertThrows(NullPointerException.class, () -> user.completeMissingPhone(new byte[]{1}, null));
        assertNull(user.getPhoneCiphertext());
        byte[] original = {1, 2}; user.completeMissingPhone(original, "synthetic-hash"); original[0] = 9;
        assertArrayEquals(new byte[]{1, 2}, user.getPhoneCiphertext());
        assertThrows(IllegalStateException.class, () -> user.completeMissingPhone(new byte[]{3}, "different"));
    }
}
