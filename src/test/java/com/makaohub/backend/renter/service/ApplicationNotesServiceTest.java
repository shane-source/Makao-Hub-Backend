package com.makaohub.backend.renter.service;

import com.makaohub.backend.admin.AdminStore;
import com.makaohub.backend.admin.StaffAccessService;
import com.makaohub.backend.admin.StaffRole;
import com.makaohub.backend.messaging.service.ChatContactMaskingService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationNotesServiceTest {
    private final JdbcTemplate sql = mock(JdbcTemplate.class);
    private final StaffAccessService staff = mock(StaffAccessService.class);
    private final AdminStore audit = mock(AdminStore.class);
    private final ApplicationNotesService service = new ApplicationNotesService(sql, new ChatContactMaskingService(), staff, audit);
    private final UUID owner = UUID.randomUUID(), application = UUID.randomUUID();

    @Test void anotherOwnerCannotHideOrWriteRemarksForTheApplication() {
        when(sql.queryForObject(contains("JOIN user_roles"), eq(Boolean.class), eq(owner), eq("LANDLORD"))).thenReturn(true);
        when(sql.queryForObject(contains("JOIN listings"), eq(Boolean.class), eq(application), eq(owner))).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> service.setHidden(owner, ApplicationNotesService.Kind.LANDLORD, application, true));
        assertThrows(ResponseStatusException.class, () -> service.setRemark(owner, ApplicationNotesService.Kind.LANDLORD, application, "Please review."));
        verify(sql, never()).update(anyString(), any(), any());
    }

    @Test void aRenterCannotReadSomeoneElsesRemark() {
        when(sql.queryForObject(contains("JOIN user_roles"), eq(Boolean.class), eq(owner), eq("RENTER"))).thenReturn(true);
        when(sql.queryForObject(contains("renter_id=?"), eq(Boolean.class), eq(application), eq(owner))).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> service.renterRemark(owner, ApplicationNotesService.Kind.SUBLESSOR, application));
    }

    @Test void remarksCannotBypassContactMasking() {
        when(sql.queryForObject(contains("JOIN user_roles"), eq(Boolean.class), eq(owner), eq("SUBLESSOR"))).thenReturn(true);
        when(sql.queryForObject(contains("JOIN sublessor_listings"), eq(Boolean.class), eq(application), eq(owner))).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> service.setRemark(owner, ApplicationNotesService.Kind.SUBLESSOR, application, "Contact me at 0712 345 678"));
        verify(sql, never()).update(anyString(), any(), any());
    }

    @Test void staffCannotReadWithoutRenterAssignment() {
        Jwt jwt = mock(Jwt.class);
        var reviewer = new StaffAccessService.Staff(UUID.randomUUID(), StaffRole.VERIFICATION_REVIEWER, true);
        when(staff.require(jwt, StaffRole.Permission.VERIFICATION)).thenReturn(reviewer);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(staff).requireAssignment(reviewer, owner, "RENTER");
        assertThrows(ResponseStatusException.class, () -> service.staffRemarksForRenter(jwt, owner));
        verifyNoInteractions(audit);
        verifyNoInteractions(sql);
    }

    @Test void hidingAndRestoringOnlyUpdatesPreference() {
        when(sql.queryForObject(contains("JOIN user_roles"), eq(Boolean.class), eq(owner), eq("LANDLORD"))).thenReturn(true);
        when(sql.queryForObject(contains("JOIN listings"), eq(Boolean.class), eq(application), eq(owner))).thenReturn(true);
        service.setHidden(owner, ApplicationNotesService.Kind.LANDLORD, application, true);
        service.setHidden(owner, ApplicationNotesService.Kind.LANDLORD, application, false);
        verify(sql).update(contains("application_owner_notes"), eq(application), eq(true));
        verify(sql).update(contains("application_owner_notes"), eq(application), eq(false));
        verify(sql, never()).update(contains("UPDATE rental_applications"), any(), any());
    }
}
