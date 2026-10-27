package com.makaohub.backend.admin;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import com.makaohub.backend.auth.service.EmailOtpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.makaohub.backend.admin.StaffInvitationService.BootstrapResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StaffBootstrapTest {
    private final AdminStore db=mock(AdminStore.class);
    private final EmailOtpClient mail=mock(EmailOtpClient.class);
    private final StaffInvitationService invitations=new StaffInvitationService(db,
            mock(StaffAccessService.class),mail,mock(PasswordEncoder.class),
            new EmailOtpProperties("sender@example.invalid",Base64.getEncoder().encodeToString(new byte[32])));

    @Test void existingStaffSkipsBeforeCheckingRecipientOrSendingEmail() {
        when(db.exists(anyString())).thenReturn(true);
        assertEquals(STAFF_EXISTS,invitations.bootstrap(""));
        var order=inOrder(db);
        order.verify(db).execute("SELECT pg_advisory_xact_lock(728162940315)");
        order.verify(db).exists("SELECT EXISTS(SELECT 1 FROM staff_accounts)");
        verifyNoMoreInteractions(db);
        verifyNoInteractions(mail);
    }

    @Test void pendingInvitationIsRetainedWithoutResendingOrChangingRecipient() {
        when(db.exists(anyString())).thenReturn(false,true);
        assertEquals(INVITATION_PENDING,invitations.bootstrap("different@example.invalid"));
        verify(db,never()).update(anyString(),any(Object[].class));
        verify(db,never()).audit(any(),anyString(),any(),anyString(),any(),any());
        verifyNoInteractions(mail);
    }

    @Test void emptySetupSendsOneInvitationWithExistingSecurityPolicy() {
        assertEquals(INVITATION_SENT,invitations.bootstrap("lead@example.invalid"));
        verify(mail).sendStaffInvitation(eq("lead@example.invalid"),matches("[A-Za-z0-9_-]{43}"));
        verifyNoMoreInteractions(mail);
        verify(db).audit(isNull(),eq("STAFF_INVITED"),any(),anyString(),eq("NONE"),eq("STAFF_LEAD"));
    }

    @Test void publicAccountStillCannotBePromoted() {
        when(db.exists(anyString())).thenReturn(false,false);
        when(db.exists(eq("SELECT EXISTS(SELECT 1 FROM users WHERE lower(email)=?)"),eq("customer@example.invalid"))).thenReturn(true);
        var failure=assertThrows(ResponseStatusException.class,()->invitations.bootstrap("customer@example.invalid"));
        assertEquals(409,failure.getStatusCode().value());
        verifyNoInteractions(mail);
    }

    @Test void unavailableDatabaseIsNotTreatedAsCompletedSetup() {
        doThrow(new DataAccessResourceFailureException("Synthetic failure")).when(db).execute(anyString());
        assertThrows(DataAccessResourceFailureException.class,()->invitations.bootstrap("lead@example.invalid"));
        verifyNoInteractions(mail);
    }

    @Test void smtpFailureIsNotReportedAsSent() {
        doThrow(new IllegalStateException("Synthetic mail failure")).when(mail).sendStaffInvitation(anyString(),anyString());
        assertThrows(IllegalStateException.class,()->invitations.bootstrap("lead@example.invalid"));
        verify(db,never()).audit(any(),anyString(),any(),anyString(),any(),any());
    }

    @Test void startupRunnerAcceptsEverySuccessfulSetupState() {
        for(var result:StaffInvitationService.BootstrapResult.values()) {
            var service=mock(StaffInvitationService.class);
            when(service.bootstrap("lead@example.invalid")).thenReturn(result);
            assertDoesNotThrow(()->new StaffBootstrap(service,"lead@example.invalid").run(new DefaultApplicationArguments()));
            verify(service).bootstrap("lead@example.invalid");
        }
    }

    @Test void resendRefusesToReplaceAnExistingStaffAccount() {
        when(db.exists(anyString())).thenReturn(true);
        assertEquals(STAFF_EXISTS,invitations.resendBootstrap("lead@example.invalid"));
        verifyNoInteractions(mail);
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void resendRequiresTheOriginalRecipient() {
        assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->invitations.resendBootstrap("other@example.invalid")).getStatusCode().value());
        verifyNoInteractions(mail);
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void resendStillRefusesPublicAccountPromotion() {
        when(db.exists(eq("SELECT EXISTS(SELECT 1 FROM users WHERE lower(email)=?)"),eq("customer@example.invalid"))).thenReturn(true);
        assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->invitations.resendBootstrap("customer@example.invalid")).getStatusCode().value());
        verifyNoInteractions(mail);
    }

    @Test void resendRejectsMissingEmailBeforeHashingOrSending() {
        assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->invitations.resendBootstrap("")).getStatusCode().value());
        verifyNoInteractions(mail);
    }

    @Test void resendCooldownPreventsDuplicateEmailAndDatabaseChanges() {
        UUID id=UUID.randomUUID();
        when(db.rows(anyString(),anyString())).thenReturn(List.of(Map.of("id",id)));
        when(db.exists(anyString(),eq(id))).thenReturn(true);
        assertEquals(429,assertThrows(ResponseStatusException.class,
                ()->invitations.resendBootstrap("lead@example.invalid")).getStatusCode().value());
        verifyNoInteractions(mail);
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void resendRotatesOnlyTheMatchingInvitationAndAuditsIt() {
        UUID id=UUID.randomUUID();
        when(db.rows(anyString(),anyString())).thenReturn(List.of(Map.of("id",id)));
        assertEquals(INVITATION_RESENT,invitations.resendBootstrap("lead@example.invalid"));
        verify(db).update(startsWith("UPDATE staff_invitations SET token_digest="),matches("[a-f0-9]{64}"),eq(id));
        verify(mail).sendStaffInvitation(eq("lead@example.invalid"),matches("[A-Za-z0-9_-]{43}"));
        verify(db).audit(isNull(),eq("STAFF_INVITATION_RESENT"),eq(id),anyString(),eq("UNACCEPTED"),eq("INVITED"));
    }

    @Test void startupResendsOnlyWithTheExplicitOperatorFlag() {
        var service=mock(StaffInvitationService.class);
        when(service.resendBootstrap("lead@example.invalid")).thenReturn(INVITATION_RESENT);
        new StaffBootstrap(service,"lead@example.invalid").run(new DefaultApplicationArguments("--resend-staff-invitation"));
        verify(service).resendBootstrap("lead@example.invalid");
        verify(service,never()).bootstrap(anyString());
    }
}
