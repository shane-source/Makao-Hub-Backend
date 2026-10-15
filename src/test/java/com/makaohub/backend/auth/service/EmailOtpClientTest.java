package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import com.makaohub.backend.auth.domain.*;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.exception.OtpProviderException;
import com.makaohub.backend.auth.repository.*;
import com.makaohub.backend.shared.error.ApiExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.makaohub.backend.auth.exception.OtpProviderException.Failure.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Mocked SMTP delivery and challenge tests. No real emails or secrets. */
class EmailOtpClientTest {
    private JavaMailSender mailer;
    private EmailOtpClient client;
    static EmailOtpProperties properties() {
        return new EmailOtpProperties("sender@example.invalid", Base64.getEncoder().encodeToString(new byte[32]));
    }
    private MailProperties mailProperties() {
        var mail = new MailProperties();
        mail.setHost("smtp.example.invalid"); mail.setPort(587);
        mail.setUsername("sender@example.invalid"); mail.setPassword("synthetic-app-password");
        mail.getProperties().putAll(Map.of("mail.smtp.auth", "true", "mail.smtp.starttls.enable", "true",
                "mail.smtp.starttls.required", "true", "mail.smtp.ssl.checkserveridentity", "true", "mail.debug", "false"));
        return mail;
    }
    @BeforeEach void setup() {
        mailer = mock(JavaMailSender.class);
        client = new EmailOtpClient(mailer, mailProperties(), properties());
    }
    @Test void rejectsMissingKeysHeaderInjectionAndRedactsConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new EmailOtpProperties("sender@example.invalid", ""));
        assertThrows(IllegalArgumentException.class, () -> new EmailOtpProperties("sender@example.invalid", "not-base64"));
        assertThrows(IllegalArgumentException.class, () -> new EmailOtpProperties("sender@example.invalid", Base64.getEncoder().encodeToString(new byte[31])));
        assertThrows(IllegalArgumentException.class, () -> new EmailOtpProperties("sender@example.invalid\r\nBcc: victim@example.invalid", properties().hmacKey()));
        assertFalse(properties().toString().contains(properties().hmacKey()));
        assertFalse(properties().toString().contains(properties().fromEmail()));
    }
    @Test void rejectsInsecureSmtpConfiguration() {
        for (String key : List.of("mail.smtp.auth", "mail.smtp.starttls.enable", "mail.smtp.starttls.required", "mail.smtp.ssl.checkserveridentity", "mail.debug")) {
            var mail = mailProperties();
            mail.getProperties().put(key, key.equals("mail.debug") ? "true" : "false");
            assertThrows(IllegalArgumentException.class, () -> new EmailOtpClient(mailer, mail, properties()));
        }
        var mail = mailProperties();
        mail.getProperties().put("mail.smtp.ssl.trust", "*");
        assertThrows(IllegalArgumentException.class, () -> new EmailOtpClient(mailer, mail, properties()));
        var missing = mailProperties(); missing.setPassword("");
        assertThrows(IllegalArgumentException.class, () -> new EmailOtpClient(mailer, missing, properties()));
    }
    @Test void sendsOneRealMailMessageForEachPurposeToTheStoredRecipient() {
        for (var purpose : AuthOtpChallengePurpose.values()) {
            client.sendCode("tester@example.invalid", "012345", purpose);
        }
        var capture = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailer, times(3)).send(capture.capture());
        for (var message : capture.getAllValues()) {
            assertEquals(properties().fromEmail(), message.getFrom());
            assertArrayEquals(new String[]{"tester@example.invalid"}, message.getTo());
            assertEquals("Makao Hub verification code", message.getSubject());
            assertTrue(message.getText().contains("012345"));
            assertTrue(message.getText().contains("five minutes"));
            assertNull(message.getBcc());
            assertNull(message.getCc());
        }
    }
    @Test void errorsAreSanitizedAndNeverRetried() {
        doThrow(new MailAuthenticationException("private credentials and recipient")).when(mailer).send(any(SimpleMailMessage.class));
        var auth = assertThrows(OtpProviderException.class, () -> client.sendCode("tester@example.invalid", "123456", AuthOtpChallengePurpose.LOGIN));
        assertEquals(CONFIGURATION, auth.getFailure()); assertNull(auth.getCause());
        assertFalse(auth.getMessage().contains("private"));
        reset(mailer);
        doThrow(new MailSendException("private code and recipient")).when(mailer).send(any(SimpleMailMessage.class));
        var failed = assertThrows(OtpProviderException.class, () -> client.sendCode("tester@example.invalid", "123456", AuthOtpChallengePurpose.LOGIN));
        assertEquals(UNAVAILABLE, failed.getFailure()); assertNull(failed.getCause());
        verify(mailer, times(1)).send(any(SimpleMailMessage.class));
    }
    @Test void malformedCodesAndRecipientsDoNotReachSmtp() {
        for (String recipient : List.of("bad", "x@example.invalid\r\nBcc: z@example.invalid")) {
            assertThrows(IllegalArgumentException.class, () -> client.sendCode(recipient, "123456", AuthOtpChallengePurpose.LOGIN));
        }
        assertThrows(IllegalArgumentException.class, () -> client.sendCode("tester@example.invalid", "12345", AuthOtpChallengePurpose.LOGIN));
        verifyNoInteractions(mailer);
    }
    @Test void publicErrorsKeepStableCodesAndEmailCopy() {
        var handler = new ApiExceptionHandler();
        for (var failure : OtpProviderException.Failure.values()) {
            var response = handler.handleOtpProviderFailure(new OtpProviderException(failure));
            assertEquals(failure == RATE_LIMITED ? 429 : 503, response.getStatusCode().value());
            assertEquals(failure.code(), response.getBody().getProperties().get("code"));
            assertTrue(failure.detail().contains("Email"));
        }
    }
    @Nested class ChallengeFlow {
        private AuthOtpChallengeRepository challenges;
        private UserRepository users;
        private RefreshTokenRepository refresh;
        private EmailOtpClient sender;
        private AuthenticationTokenService tokens;
        private PasswordEncoder passwords;
        private AuthOtpService service;
        private User user;
        private final Instant issued = Instant.parse("2026-10-13T12:00:00Z");
        private final AtomicReference<Instant> now = new AtomicReference<>(issued);
        private String code;
        private final AtomicReference<AuthOtpChallenge> saved = new AtomicReference<>();

        @BeforeEach void prepare() {
            challenges = mock(AuthOtpChallengeRepository.class); users = mock(UserRepository.class);
            refresh = mock(RefreshTokenRepository.class); sender = mock(EmailOtpClient.class);
            tokens = mock(AuthenticationTokenService.class); passwords = mock(PasswordEncoder.class);
            user = new User("Test User", "tester@example.invalid", "old-password-hash", new byte[]{1}, "synthetic-lookup");
            ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
            when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
            when(users.findByEmailIgnoreCaseForUpdate(user.getEmail())).thenReturn(Optional.of(user));
            when(challenges.save(any())).thenAnswer(call -> { saved.set(call.getArgument(0)); return saved.get(); });
            doAnswer(call -> { code = call.getArgument(1); return null; })
                    .when(sender).sendCode(anyString(), anyString(), any());
            Clock clock = mock(Clock.class); when(clock.instant()).thenAnswer(call -> now.get());
            service = new AuthOtpService(challenges, users, refresh, properties(), sender, tokens, passwords, clock);
        }

        private AuthOtpChallenge issue(AuthOtpChallengePurpose purpose) {
            switch (purpose) {
                case LOGIN -> service.startLogin(user);
                case REGISTRATION -> service.startRegistration(user);
                case PASSWORD_RESET -> service.requestPasswordReset(user.getEmail());
            }
            var challenge = saved.get();
            when(challenges.findUserIdByChallengeId(challenge.getId())).thenReturn(Optional.of(user.getId()));
            when(challenges.findByIdForUpdate(challenge.getId())).thenReturn(Optional.of(challenge));
            when(challenges.findFirstByUser_IdAndPurposeAndStatusOrderByIssuedAtDesc(user.getId(), purpose, AuthOtpChallengeStatus.PENDING))
                    .thenReturn(Optional.of(challenge));
            return challenge;
        }

        @Test void onlyDigestIsStoredAndOnlySuccessfulVerificationIssuesTokens() {
            var response = service.startRegistration(user);
            assertEquals("••••@example.invalid", response.maskedEmail());
            assertEquals(issued.plusSeconds(300), response.expiresAt());
            var challenge = saved.get();
            assertTrue(code.matches("[0-9]{6}"));
            assertTrue(challenge.getCodeDigest().matches("[0-9a-f]{64}"));
            assertNotEquals(code, challenge.getCodeDigest());
            verifyNoInteractions(tokens);
            when(challenges.findUserIdByChallengeId(challenge.getId())).thenReturn(Optional.of(user.getId()));
            when(challenges.findByIdForUpdate(challenge.getId())).thenReturn(Optional.of(challenge));
            service.verifyRegistration(challenge.getId(), code);
            assertEquals(AuthOtpChallengeStatus.VERIFIED, challenge.getStatus()); verify(tokens).issue(user);
            assertThrows(AuthException.class, () -> service.verifyRegistration(challenge.getId(), code));
            verify(tokens, times(1)).issue(user);
        }

        @Test void wrongCodesConsumeFiveAttemptsAndCannotGrantASession() {
            var challenge = issue(AuthOtpChallengePurpose.LOGIN);
            String wrong = code.equals("000000") ? "000001" : "000000";
            for (int i = 0; i < 5; i++) assertThrows(AuthException.class, () -> service.verifyLogin(challenge.getId(), wrong));
            assertEquals(5, challenge.getAttemptCount()); assertEquals(AuthOtpChallengeStatus.LOCKED, challenge.getStatus());
            assertThrows(AuthException.class, () -> service.verifyLogin(challenge.getId(), code));
            verifyNoInteractions(tokens);
        }

        @Test void expiryAndPurposeCannotBeBypassed() {
            var challenge = issue(AuthOtpChallengePurpose.REGISTRATION);
            assertThrows(AuthException.class, () -> service.verifyLogin(challenge.getId(), code));
            now.set(issued.plusSeconds(300));
            assertThrows(AuthException.class, () -> service.verifyRegistration(challenge.getId(), code));
            assertEquals(AuthOtpChallengeStatus.EXPIRED, challenge.getStatus()); verifyNoInteractions(tokens);
        }

        @Test void disabledAccountsAndChangedAuthorizationCannotUseAnIssuedCode() {
            var challenge = issue(AuthOtpChallengePurpose.LOGIN);
            user.invalidateAuthorization();
            assertThrows(AuthException.class, () -> service.verifyLogin(challenge.getId(), code));
            ReflectionTestUtils.setField(user, "accountStatus", AccountStatus.SUSPENDED);
            assertThrows(AuthException.class, () -> service.verifyLogin(challenge.getId(), code));
            verifyNoInteractions(tokens);
        }

        @Test void copiedDigestCannotVerifyAnotherChallenge() {
            var original = issue(AuthOtpChallengePurpose.LOGIN);
            var other = new AuthOtpChallenge(user, AuthOtpChallengePurpose.LOGIN, 0, issued, issued.plusSeconds(300));
            other.setCodeDigest(original.getCodeDigest());
            when(challenges.findUserIdByChallengeId(other.getId())).thenReturn(Optional.of(user.getId()));
            when(challenges.findByIdForUpdate(other.getId())).thenReturn(Optional.of(other));
            assertThrows(AuthException.class, () -> service.verifyLogin(other.getId(), code));
            verifyNoInteractions(tokens);
        }

        @Test void failedSendDoesNotSaveOrInvalidatePreviousChallenge() {
            var previous = new AuthOtpChallenge(user, AuthOtpChallengePurpose.LOGIN, 0, issued, issued.plusSeconds(300));
            previous.setCodeDigest("a".repeat(64));
            when(challenges.findFirstByUser_IdAndPurposeAndStatusOrderByIssuedAtDesc(user.getId(), AuthOtpChallengePurpose.LOGIN, AuthOtpChallengeStatus.PENDING))
                    .thenReturn(Optional.of(previous));
            doThrow(new OtpProviderException(UNAVAILABLE)).when(sender).sendCode(anyString(), anyString(), any());
            assertThrows(OtpProviderException.class, () -> service.startLogin(user));
            verify(challenges, never()).save(any()); assertEquals(AuthOtpChallengeStatus.PENDING, previous.getStatus());
            verifyNoInteractions(tokens);
        }

        @Test void sendLimitIsCheckedBeforeEmailAndLegacySmsRowsFailClosed() {
            when(challenges.countByUser_IdAndPurposeAndIssuedAtAfter(eq(user.getId()), eq(AuthOtpChallengePurpose.LOGIN), any())).thenReturn(3L);
            assertThrows(AuthException.class, () -> service.startLogin(user)); verifyNoInteractions(sender);
            var legacy = new AuthOtpChallenge(user, AuthOtpChallengePurpose.LOGIN, 0, issued, issued.plusSeconds(300));
            assertFalse(legacy.isUsable(issued));
            when(challenges.findUserIdByChallengeId(legacy.getId())).thenReturn(Optional.of(user.getId()));
            when(challenges.findByIdForUpdate(legacy.getId())).thenReturn(Optional.of(legacy));
            assertThrows(AuthException.class, () -> service.verifyLogin(legacy.getId(), "123456")); verifyNoInteractions(tokens);
        }

        @Test void resetUsesEmailCodeOnceChangesPasswordAndRevokesSessions() {
            var challenge = issue(AuthOtpChallengePurpose.PASSWORD_RESET);
            when(passwords.encode("new-test-password")).thenReturn("new-password-hash");
            service.resetPassword(user.getEmail(), code, "new-test-password");
            assertEquals("new-password-hash", user.getPasswordHash()); assertEquals(1, user.getAuthorizationVersion());
            assertEquals(AuthOtpChallengeStatus.VERIFIED, challenge.getStatus());
            verify(refresh).revokeAllActiveForUserWithReason(user.getId(), issued, "PASSWORD_RESET");
            assertThrows(AuthException.class, () -> service.resetPassword(user.getEmail(), code, "new-test-password"));
            verifyNoInteractions(tokens);
        }

        @Test void resetRejectsSevenAndOversizedPasswordsWithoutConsumingCode() {
            var challenge = issue(AuthOtpChallengePurpose.PASSWORD_RESET);
            for (String invalid : List.of("1234567", "a".repeat(73), "é".repeat(40), " ".repeat(8))) {
                assertThrows(AuthException.class, () -> service.resetPassword(user.getEmail(), code, invalid));
            }
            assertEquals(AuthOtpChallengeStatus.PENDING, challenge.getStatus());
            verifyNoInteractions(passwords);
            when(passwords.encode("12345678")).thenReturn("eight-character-hash");
            service.resetPassword(user.getEmail(), code, "12345678");
            assertEquals("eight-character-hash", user.getPasswordHash());
        }

        @Test void passwordResetResponseDoesNotDiscloseAccountOrProviderFailure() {
            String absent = service.requestPasswordReset("absent@example.invalid").message();
            doThrow(new OtpProviderException(CONFIGURATION)).when(sender).sendCode(anyString(), anyString(), any());
            assertEquals(absent, service.requestPasswordReset(user.getEmail()).message());
            verify(challenges, never()).save(any());
        }
    }
}
