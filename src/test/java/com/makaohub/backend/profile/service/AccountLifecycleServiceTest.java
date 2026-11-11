package com.makaohub.backend.profile.service;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.AuthOtpChallengePurpose;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.RefreshTokenRepository;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.service.AuthOtpService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountLifecycleServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final AuthOtpService otp = mock(AuthOtpService.class);
    private final RefreshTokenRepository refresh = mock(RefreshTokenRepository.class);
    private final AccountDeletionRequestService requests = mock(AccountDeletionRequestService.class);
    private final JdbcTemplate sql = mock(JdbcTemplate.class);
    private final AccountLifecycleService service = new AccountLifecycleService(
            users, otp, refresh, requests, sql, Clock.systemUTC());
    private final UUID userId = UUID.randomUUID();
    private final UUID challengeId = UUID.randomUUID();
    private final User user = mock(User.class);

    @Test
    void openMoneyBlocksCodeAndStatusChange() {
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(user.getEmail()).thenReturn("user@example.test");
        when(sql.queryForObject(anyString(), eq(Boolean.class), any(), any(), any(), any())).thenReturn(true);
        assertThrows(ResponseStatusException.class,
                () -> service.start(userId, "user@example.test", AuthOtpChallengePurpose.ACCOUNT_DELETION));
        verifyNoInteractions(otp, requests, refresh);
    }

    @Test
    void confirmedDeletionOpensReviewAndInvalidatesAccessWithoutErasingUser() {
        when(otp.verifyAccountAction(userId, challengeId, "123456", AuthOtpChallengePurpose.ACCOUNT_DELETION))
                .thenReturn(user);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(user.getEmail()).thenReturn("user@example.test");
        when(sql.queryForObject(anyString(), eq(Boolean.class), any(), any(), any(), any())).thenReturn(false);
        service.confirm(userId, "user@example.test", challengeId, "123456", AuthOtpChallengePurpose.ACCOUNT_DELETION);
        verify(requests).request(userId);
        verify(user).changeSelfServiceStatus(AccountStatus.DELETION_PENDING);
        verify(refresh).revokeAllActiveForUserWithReason(eq(userId), any(), eq("SELF_SERVICE_ACCOUNT_STATUS"));
        verify(users, never()).delete(any(User.class));
    }

    @Test
    void confirmedDeactivationDoesNotOpenDeletionCase() {
        when(otp.verifyAccountAction(userId, challengeId, "123456", AuthOtpChallengePurpose.ACCOUNT_DEACTIVATION))
                .thenReturn(user);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(user.getEmail()).thenReturn("user@example.test");
        when(sql.queryForObject(anyString(), eq(Boolean.class), any(), any(), any(), any())).thenReturn(false);
        service.confirm(userId, "user@example.test", challengeId, "123456", AuthOtpChallengePurpose.ACCOUNT_DEACTIVATION);
        verify(user).changeSelfServiceStatus(AccountStatus.DEACTIVATED);
        verifyNoInteractions(requests);
    }
}
