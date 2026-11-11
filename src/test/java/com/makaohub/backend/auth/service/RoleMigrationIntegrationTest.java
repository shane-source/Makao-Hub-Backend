package com.makaohub.backend.auth.service;

import com.makaohub.backend.auth.config.CurrentAccountJwtValidator;
import com.makaohub.backend.auth.config.EmailOtpProperties;
import com.makaohub.backend.auth.domain.*;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.*;
import com.makaohub.backend.verification.domain.*;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

// Deliberately refuses production URLs. Each run owns a fresh schema in a local test database.
@EnabledIfEnvironmentVariable(named = "MAKAO_ROLE_TEST_DB_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleMigrationIntegrationTest {
    private final String schema = "role_test_" + UUID.randomUUID().toString().replace("-", "");
    private final UUID legacy = UUID.randomUUID();
    private final UUID concurrent = UUID.randomUUID();
    private final UUID rollback = UUID.randomUUID();
    private final UUID suspended = UUID.randomUUID();
    private final UUID renterChoice = UUID.randomUUID();
    private final UUID sublessorChoice = UUID.randomUUID();
    private final UUID landlord = UUID.randomUUID();
    private JdbcTemplate sql;
    private DriverManagerDataSource dataSource;
    private LocalContainerEntityManagerFactoryBean factoryBean;
    private EntityManager em;
    private TransactionTemplate tx;
    private UserRepository users;
    private UserRoleRepository userRoles;
    private RoleRepository roles;
    private RoleVerificationRepository verifications;
    private LegacyRoleSelectionService selection;

    @BeforeAll
    void prepareDatabase() {
        String url = System.getenv("MAKAO_ROLE_TEST_DB_URL");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/makao_role_test"),
                "Use a dedicated local makao_role_test database.");
        dataSource = new DriverManagerDataSource(url + "?currentSchema=" + schema,
                System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_USER", "hp"),
                System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_PASSWORD", ""));
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .target("14").load().migrate();
        sql = new JdbcTemplate(dataSource);
        for (UUID id : List.of(legacy, concurrent, rollback, suspended, renterChoice, sublessorChoice)) {
            insertUser(id, "ROOMMATE_SEEKER", id.equals(suspended) ? "SUSPENDED" : "ACTIVE");
        }
        insertUser(landlord, "LANDLORD", "ACTIVE");
        sql.update("INSERT INTO landlord_verifications (user_id, status) VALUES (?, 'VERIFIED')", landlord);
        sql.update("""
                INSERT INTO refresh_tokens (user_id, token_hash, token_family_id, expires_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '1 day')
                """, legacy, "a".repeat(64), UUID.randomUUID());
        Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).load().migrate();

        factoryBean = new LocalContainerEntityManagerFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setPackagesToScan("com.makaohub.backend.auth.domain", "com.makaohub.backend.verification.domain");
        factoryBean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factoryBean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
        factoryBean.afterPropertiesSet();
        EntityManagerFactory emf = Objects.requireNonNull(factoryBean.getObject());
        em = SharedEntityManagerCreator.createSharedEntityManager(emf);
        tx = new TransactionTemplate(new JpaTransactionManager(emf));
        var repositoryFactory = new JpaRepositoryFactory(em);
        users = repositoryFactory.getRepository(UserRepository.class);
        roles = repositoryFactory.getRepository(RoleRepository.class);
        userRoles = repositoryFactory.getRepository(UserRoleRepository.class);
        verifications = repositoryFactory.getRepository(RoleVerificationRepository.class);
        selection = new LegacyRoleSelectionService(em, roles, userRoles);
    }

    private void insertUser(UUID id, String role, String status) {
        sql.update("""
                INSERT INTO users (id, full_name, email, password_hash, phone_ciphertext, phone_lookup_hash, account_status)
                VALUES (?, 'Test User', ?, 'synthetic-hash', ?, ?, ?)
                """, id, id + "@example.invalid", new byte[]{1}, id.toString(), status);
        sql.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = ?", id, role);
    }

    @Test void passwordAndGoogleLoginOnlyAcceptTheStoredRoleWithoutMutatingIt() {
        var tokenIssuer = mock(AuthenticationTokenService.class);
        var encoder = mock(PasswordEncoder.class);
        when(encoder.matches("correct-password", "synthetic-hash")).thenReturn(true);
        var passwordLogin = new PasswordAuthenticationService(users, encoder, tokenIssuer, selection);
        var verifier = mock(GoogleIdTokenVerifier.class);
        var identities = mock(GoogleIdentityRepository.class);
        var googleLogin = new GoogleAuthenticationService(verifier, identities, tokenIssuer, selection, users, mock(AuthOtpService.class));
        for (RoleName stored : List.of(RoleName.LANDLORD, RoleName.SUBLESSOR, RoleName.RENTER)) {
            UUID id = UUID.randomUUID();
            insertUser(id, stored.name(), "ACTIVE");
            for (RoleName expected : RoleName.values()) {
                tx.executeWithoutResult(status -> {
                    User account = users.findById(id).orElseThrow();
                    when(verifier.verify("synthetic-token")).thenReturn(
                            new GoogleIdTokenVerifier.VerifiedGoogleIdentity("synthetic-subject", id+"@example.invalid", "Synthetic"));
                    when(identities.findByGoogleSubject("synthetic-subject"))
                            .thenReturn(Optional.of(new GoogleIdentity(account, "synthetic-subject")));
                    var request = new com.makaohub.backend.auth.api.LoginRequest(id+"@example.invalid", "correct-password", expected);
                    if (stored == expected) {
                        assertEquals(id, passwordLogin.verifyCredentials(request).getId());
                        assertEquals(id, googleLogin.verifyIdentity("synthetic-token", expected).getId());
                    } else {
                        assertEquals(AuthException.Reason.ROLE_MISMATCH,
                                assertThrows(AuthException.class, () -> passwordLogin.verifyCredentials(request)).getReason());
                        assertEquals(AuthException.Reason.ROLE_MISMATCH,
                                assertThrows(AuthException.class, () -> googleLogin.verifyIdentity("synthetic-token", expected)).getReason());
                    }
                    assertEquals(stored, userRoles.findAllByUser_Id(id).getFirst().getRole().getName());
                    assertEquals(0L, account.getAuthorizationVersion());
                    assertEquals(VerificationStatus.NOT_STARTED,
                            verifications.findByUserIdAndRole(id, stored).orElseThrow().getStatus());
                    assertEquals(AuthException.Reason.INVALID_CREDENTIALS, assertThrows(AuthException.class,
                            () -> passwordLogin.verifyCredentials(new com.makaohub.backend.auth.api.LoginRequest(
                                    id+"@example.invalid", "wrong-password", expected))).getReason());
                });
            }
            assertEquals(1, sql.queryForObject("SELECT count(*) FROM role_verifications WHERE user_id=?", Integer.class, id));
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM auth_otp_challenges WHERE user_id=?", Integer.class, id));
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id=?", Integer.class, id));
        }
        verifyNoInteractions(tokenIssuer);
    }

    @AfterAll
    void closeDatabase() {
        if (factoryBean != null) factoryBean.destroy();
        if (dataSource != null) {
            // Only this test's random schema is removed, never public or a caller-provided schema.
            new JdbcTemplate(dataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test void phoneOptionalSchemaSupportsGoogleAccountsAndPreservesExistingPhones() {
        for (int i = 0; i < 2; i++) {
            UUID id = tx.execute(status -> users.saveAndFlush(new User(
                    "Synthetic Google Account", UUID.randomUUID() + "@example.invalid", null, null)).getId());
            tx.executeWithoutResult(status -> {
                User user = users.findById(id).orElseThrow();
                assertNull(user.getPhoneCiphertext());
                assertNull(user.getPhoneLookupHash());
            });
        }
        assertArrayEquals(new byte[]{1}, sql.queryForObject(
                "SELECT phone_ciphertext FROM users WHERE id=?", byte[].class, landlord));
        assertEquals(landlord.toString(), sql.queryForObject(
                "SELECT phone_lookup_hash FROM users WHERE id=?", String.class, landlord));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> sql.update("""
                INSERT INTO users(id,full_name,email,phone_ciphertext,account_status)
                VALUES(?,'Synthetic',?,?,'ACTIVE')
                """, UUID.randomUUID(), UUID.randomUUID()+"@example.invalid", new byte[]{1}));
    }

    @Test void newGoogleRegistrationsPersistRealProtectedPhoneForEveryPublicRoleAndRejectDuplicates() {
        var verifier = mock(GoogleIdTokenVerifier.class);
        var identities = new JpaRepositoryFactory(em).getRepository(GoogleIdentityRepository.class);
        var legal = mock(LegalAcceptanceService.class);
        var otp = mock(AuthOtpService.class);
        // Synthetic local keys only: never load the application's environment or customer data.
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        var protection = new PhoneProtectionService(new com.makaohub.backend.auth.config.PhoneSecurityProperties(testKey, testKey));
        var registration = new GoogleRegistrationService(verifier, identities, users, roles, userRoles, protection, legal, otp);
        var challenge = new com.makaohub.backend.auth.api.OtpChallengeResponse(UUID.randomUUID(),
                AuthOtpChallengePurpose.REGISTRATION, "••••@example.invalid", java.time.Instant.now().plusSeconds(300));
        when(otp.startRegistration(any())).thenReturn(challenge);
        for (RoleName role : List.of(RoleName.RENTER, RoleName.LANDLORD, RoleName.SUBLESSOR)) {
            String email = UUID.randomUUID() + "@example.invalid";
            String subject = UUID.randomUUID().toString();
            String number = "+25471000000" + role.ordinal();
            when(verifier.verify("synthetic-phone-token")).thenReturn(
                    new GoogleIdTokenVerifier.VerifiedGoogleIdentity(subject, email, "Synthetic Google User"));
            tx.executeWithoutResult(status -> {
                assertSame(challenge, registration.register(new com.makaohub.backend.auth.api.GoogleRegistrationRequest(
                        "synthetic-phone-token", null, number, role, true)));
            });
            tx.executeWithoutResult(status -> {
                User user = users.findByEmailIgnoreCase(email).orElseThrow();
                assertEquals(number, protection.decrypt(user.getPhoneCiphertext()));
                assertNotNull(user.getPhoneLookupHash());
                assertNull(user.getPasswordHash()); // Google login does not invent a password.
                assertEquals(user.getId(), identities.findByGoogleSubject(subject).orElseThrow().getUser().getId());
                assertEquals(role, userRoles.findAllByUser_Id(user.getId()).getFirst().getRole().getName());
                assertEquals(VerificationStatus.NOT_STARTED,
                        verifications.findByUserIdAndRole(user.getId(), role).orElseThrow().getStatus());
            });
            long count = users.count();
            when(verifier.verify("duplicate-phone-token")).thenReturn(new GoogleIdTokenVerifier.VerifiedGoogleIdentity(
                    UUID.randomUUID().toString(), UUID.randomUUID() + "@example.invalid", "Another Synthetic User"));
            var error = assertThrows(com.makaohub.backend.auth.exception.RegistrationException.class,
                    () -> tx.executeWithoutResult(status -> registration.register(
                            new com.makaohub.backend.auth.api.GoogleRegistrationRequest(
                                    "duplicate-phone-token", null, number, role, true))));
            assertEquals(com.makaohub.backend.auth.exception.RegistrationException.Reason.PHONE_ALREADY_REGISTERED, error.getReason());
            assertEquals(count, users.count());
        }
        verify(otp, times(3)).startRegistration(any());
    }

    @Test void phoneCompletionSerializesSameAccountAndUniqueConstraintProtectsDifferentAccounts() throws Exception {
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        var protection = new PhoneProtectionService(new com.makaohub.backend.auth.config.PhoneSecurityProperties(testKey, testKey));
        var completion = new com.makaohub.backend.profile.service.ProfileService(users, userRoles, protection,
                mock(com.makaohub.backend.storage.service.SupabaseStorageClient.class), verifications);
        UUID first = missingPhoneAccount();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Future<Boolean> a = pool.submit(() -> completeConcurrently(completion, first, "0710000100", start));
            Future<Boolean> b = pool.submit(() -> completeConcurrently(completion, first, "0710000101", start));
            start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            String saved = tx.execute(status -> protection.decrypt(users.findById(first).orElseThrow().getPhoneCiphertext()));
            assertTrue(List.of("+254710000100", "+254710000101").contains(saved));
            tx.executeWithoutResult(status -> completion.completeMissingPhone(first, saved)); // Replayed same request succeeds.

            UUID second = missingPhoneAccount(), third = missingPhoneAccount();
            var separate = new CountDownLatch(1);
            Future<Boolean> c = pool.submit(() -> completeConcurrently(completion, second, "0710000102", separate));
            Future<Boolean> d = pool.submit(() -> completeConcurrently(completion, third, "0710000102", separate));
            separate.countDown();
            assertNotEquals(c.get(10, TimeUnit.SECONDS), d.get(10, TimeUnit.SECONDS));
            assertEquals(1, sql.queryForObject("SELECT count(*) FROM users WHERE phone_lookup_hash=?",
                    Integer.class, protection.protect("+254710000102").lookupHash()));
        } finally { pool.shutdownNow(); }
    }

    private UUID missingPhoneAccount() {
        return tx.execute(status -> {
            User account = users.saveAndFlush(new User("Synthetic Legacy Account", UUID.randomUUID()+"@example.invalid", null, null));
            userRoles.saveAndFlush(new UserRole(account, roles.findByName(RoleName.RENTER).orElseThrow(), null));
            return account.getId();
        });
    }

    private boolean completeConcurrently(com.makaohub.backend.profile.service.ProfileService service, UUID owner,
                                         String phone, CountDownLatch start) throws InterruptedException {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            tx.executeWithoutResult(status -> service.completeMissingPhone(owner, phone));
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            return false;
        } catch (org.hibernate.exception.ConstraintViolationException conflict) {
            // This isolated harness creates repositories directly, without Spring's exception-translation postprocessor.
            assertEquals("23505", conflict.getSQLState());
            assertEquals("users_phone_lookup_hash_unique", conflict.getConstraintName());
            return false;
        } catch (com.makaohub.backend.auth.exception.RegistrationException conflict) {
            assertEquals(com.makaohub.backend.auth.exception.RegistrationException.Reason.PHONE_ALREADY_REGISTERED, conflict.getReason());
            return false;
        } catch (org.springframework.web.server.ResponseStatusException conflict) {
            assertEquals(409, conflict.getStatusCode().value());
            return false;
        }
    }

    @Test void migrationPreservesAccountsAndRequiresAnExplicitChoice() {
        tx.executeWithoutResult(status -> {
            User user = users.findById(legacy).orElseThrow();
            assertTrue(user.isRoleSelectionRequired());
            assertEquals(AccountStatus.ACTIVE, user.getAccountStatus());
            assertEquals(1L, user.getAuthorizationVersion());
            assertTrue(userRoles.findAllByUser_Id(legacy).isEmpty());
            assertEquals(AccountStatus.SUSPENDED, users.findById(suspended).orElseThrow().getAccountStatus());
            assertEquals(VerificationStatus.VERIFIED,
                    verifications.findByUserIdAndRole(landlord, RoleName.LANDLORD).orElseThrow().getStatus());
        });
        assertEquals(0, sql.queryForObject("SELECT count(*) FROM roles WHERE name = 'ROOMMATE_SEEKER'", Integer.class));
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NOT NULL",
                Integer.class, legacy));
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM role_selection_audit WHERE user_id = ? AND event = 'CHOICE_REQUIRED'",
                Integer.class, legacy));
    }

    @Test void bothChoicesCreateOnlyTheirOwnNotStartedVerification() {
        for (var entry : Map.of(renterChoice, RoleName.RENTER, sublessorChoice, RoleName.SUBLESSOR).entrySet()) {
            tx.executeWithoutResult(status -> selection.resolve(users.findById(entry.getKey()).orElseThrow(), entry.getValue()));
            tx.executeWithoutResult(status -> {
                User user = users.findById(entry.getKey()).orElseThrow();
                assertFalse(user.isRoleSelectionRequired());
                assertEquals(2L, user.getAuthorizationVersion());
                assertEquals(entry.getValue(), userRoles.findAllByUser_Id(user.getId()).getFirst().getRole().getName());
                assertEquals(VerificationStatus.NOT_STARTED,
                        verifications.findByUserIdAndRole(user.getId(), entry.getValue()).orElseThrow().getStatus());
                var validator = new CurrentAccountJwtValidator(userRoles);
                Jwt current = token(user.getId(), entry.getValue(), 2);
                assertFalse(validator.validate(current).hasErrors());
                assertTrue(validator.validate(token(user.getId(), entry.getValue(), 1)).hasErrors());
            });
            assertEquals(1, sql.queryForObject("SELECT count(*) FROM role_selection_audit WHERE user_id = ? AND event = 'ROLE_SELECTED'",
                    Integer.class, entry.getKey()));
        }
    }

    private Jwt token(UUID id, RoleName role, long version) {
        return Jwt.withTokenValue("test").header("alg", "RS256").subject(id.toString())
                .claim("token_type", "access").claim("roles", List.of(role.name()))
                .claim("authorization_version", version).build();
    }

    @Test void rollbackRemovesChoiceVerificationAndAuditTogether() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            selection.resolve(users.findById(rollback).orElseThrow(), RoleName.RENTER);
            throw new IllegalStateException("Simulated token issuance failure");
        }));
        tx.executeWithoutResult(status -> {
            assertTrue(users.findById(rollback).orElseThrow().isRoleSelectionRequired());
            assertTrue(userRoles.findAllByUser_Id(rollback).isEmpty());
            assertTrue(verifications.findByUserIdAndRole(rollback, RoleName.RENTER).isEmpty());
        });
        assertEquals(0, sql.queryForObject("SELECT count(*) FROM role_selection_audit WHERE user_id = ? AND event = 'ROLE_SELECTED'",
                Integer.class, rollback));
    }

    @Test void simultaneousChoicesHaveExactlyOneWinner() throws Exception {
        CountDownLatch loaded = new CountDownLatch(2);
        Callable<Boolean> chooseRenter = choice(concurrent, RoleName.RENTER, loaded);
        Callable<Boolean> chooseSublessor = choice(concurrent, RoleName.SUBLESSOR, loaded);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(chooseRenter);
            Future<Boolean> second = executor.submit(chooseSublessor);
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM user_roles WHERE user_id = ?", Integer.class, concurrent));
        assertEquals(1, sql.queryForObject("SELECT count(*) FROM role_selection_audit WHERE user_id = ? AND event = 'ROLE_SELECTED'",
                Integer.class, concurrent));
    }

    private Callable<Boolean> choice(UUID id, RoleName role, CountDownLatch loaded) {
        return () -> {
            try {
                tx.executeWithoutResult(status -> {
                    User user = users.findById(id).orElseThrow();
                    loaded.countDown();
                    try {
                        if (!loaded.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timed out");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    selection.resolve(user, role);
                });
                return true;
            } catch (AuthException exception) {
                assertEquals(AuthException.Reason.ROLE_SELECTION_NOT_REQUIRED, exception.getReason());
                return false;
            }
        };
    }

    @Test void verificationUpdatesCannotChangeAnotherRolesStatus() {
        tx.executeWithoutResult(status -> {
            verifications.saveAndFlush(new RoleVerification(landlord, RoleName.SUBLESSOR));
            verifications.findByUserIdAndRole(landlord, RoleName.SUBLESSOR).orElseThrow().markPending();
        });
        tx.executeWithoutResult(status -> {
            assertEquals(VerificationStatus.VERIFIED,
                    verifications.findByUserIdAndRole(landlord, RoleName.LANDLORD).orElseThrow().getStatus());
            assertEquals(VerificationStatus.PENDING,
                    verifications.findByUserIdAndRole(landlord, RoleName.SUBLESSOR).orElseThrow().getStatus());
        });
    }

    @Test void newJpaAssignmentsInitializeSeparateUnverifiedRoles() {
        for (RoleName role : List.of(RoleName.RENTER, RoleName.LANDLORD, RoleName.SUBLESSOR)) {
            UUID userId = tx.execute(status -> {
                User user = users.saveAndFlush(new User("New User", UUID.randomUUID() + "@example.invalid",
                        "synthetic-password-hash", new byte[]{1}, UUID.randomUUID().toString()));
                userRoles.saveAndFlush(new UserRole(user, roles.findByName(role).orElseThrow(), null));
                return user.getId();
            });
            tx.executeWithoutResult(status -> {
                assertEquals(VerificationStatus.NOT_STARTED,
                        verifications.findByUserIdAndRole(userId, role).orElseThrow().getStatus());
                assertFalse(users.findById(userId).orElseThrow().isRoleSelectionRequired());
            });
        }
    }

    @Test void databaseRejectsUnassignedChoiceAndAuditTampering() {
        assertThrows(org.springframework.dao.DataAccessException.class, () ->
                sql.update("UPDATE users SET role_selection_required = FALSE, authorization_version = 2 WHERE id = ?", legacy));
        assertThrows(org.springframework.dao.DataAccessException.class, () ->
                sql.update("DELETE FROM role_selection_audit WHERE user_id = ?", legacy));
        assertThrows(org.springframework.dao.DataAccessException.class, () ->
                sql.update("UPDATE role_selection_audit SET selected_role = 'RENTER' WHERE user_id = ?", legacy));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> sql.execute("TRUNCATE role_selection_audit"));
    }

    @Test void accountAuthorizationQueryRejectsPendingSuspendedAndWrongRole() {
        tx.executeWithoutResult(status -> {
            assertFalse(userRoles.isCurrentAuthorization(legacy, RoleName.RENTER, 1));
            assertFalse(userRoles.isCurrentAuthorization(suspended, RoleName.RENTER, 1));
            assertTrue(userRoles.isCurrentAuthorization(landlord, RoleName.LANDLORD, 0));
            assertFalse(userRoles.isCurrentAuthorization(landlord, RoleName.RENTER, 0));
        });
        UUID restricted = UUID.randomUUID();
        insertUser(restricted, "RENTER", "SUSPENDED");
        tx.executeWithoutResult(status -> assertFalse(userRoles.isCurrentAuthorization(restricted, RoleName.RENTER, 0)));
    }

    @Test void emailOtpFailureCountsPersistAndConcurrentConsumptionHasOneWinner() throws Exception {
        UUID owner = tx.execute(status -> {
            User user = users.saveAndFlush(new User("Email Test", UUID.randomUUID() + "@example.invalid",
                    "synthetic-password", new byte[]{1}, UUID.randomUUID().toString()));
            userRoles.saveAndFlush(new UserRole(user, roles.findByName(RoleName.LANDLORD).orElseThrow(), null));
            return user.getId();
        });
        var repositories = new JpaRepositoryFactory(em);
        var challenges = repositories.getRepository(AuthOtpChallengeRepository.class);
        var refresh = repositories.getRepository(RefreshTokenRepository.class);
        var sender = mock(EmailOtpClient.class);
        var tokens = mock(AuthenticationTokenService.class);
        var sentCode = new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call -> { sentCode.set(call.getArgument(1)); return null; }).when(sender).sendCode(anyString(), anyString(), any());
        var properties = new EmailOtpProperties("sender@example.invalid", Base64.getEncoder().encodeToString(new byte[32]));
        var service = new AuthOtpService(challenges, users, refresh, properties, sender, tokens, mock(PasswordEncoder.class), Clock.systemUTC(),
                repositories.getRepository(GoogleIdentityRepository.class));
        UUID id = tx.execute(status -> service.startLogin(users.findById(owner).orElseThrow()).challengeId());
        String correct = sentCode.get();
        assertTrue(correct.matches("[0-9]{6}"));
        String wrong = correct.equals("000000") ? "000001" : "000000";
        // Catch inside the transaction to model noRollbackFor=AuthException on the service.
        tx.executeWithoutResult(status -> assertThrows(AuthException.class, () -> service.verifyLogin(id, wrong)));
        assertEquals(1, sql.queryForObject("SELECT attempt_count FROM auth_otp_challenges WHERE id = ?", Integer.class, id));
        assertNull(sql.queryForObject("SELECT provider_pin_id FROM auth_otp_challenges WHERE id = ?", String.class, id));
        assertTrue(sql.queryForObject("SELECT code_digest FROM auth_otp_challenges WHERE id = ?", String.class, id).matches("[0-9a-f]{64}"));
        var start = new CountDownLatch(1);
        Callable<Boolean> consume = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return tx.execute(status -> {
                try { service.verifyLogin(id, correct); return true; }
                catch (AuthException invalid) { return false; }
            });
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(consume);
            Future<Boolean> second = executor.submit(consume);
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        verify(tokens, times(1)).issue(any());
        assertEquals("VERIFIED", sql.queryForObject("SELECT status FROM auth_otp_challenges WHERE id = ?", String.class, id));
        assertEquals(VerificationStatus.NOT_STARTED, tx.execute(status -> verifications.findByUserIdAndRole(owner, RoleName.LANDLORD).orElseThrow().getStatus()));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> sql.update("UPDATE auth_otp_challenges SET code_digest = 'plaintext' WHERE id = ?", id));

        // A login challenge must not survive an authorization change while the email is in flight.
        UUID staleId = tx.execute(status -> service.startLogin(users.findById(owner).orElseThrow()).challengeId());
        String staleCode = sentCode.get();
        tx.executeWithoutResult(status -> users.findById(owner).orElseThrow().invalidateAuthorization());
        tx.executeWithoutResult(status -> assertEquals(AuthException.Reason.INVALID_OTP,
                assertThrows(AuthException.class, () -> service.verifyLogin(staleId, staleCode)).getReason()));
        verify(tokens, times(1)).issue(any());
    }

    @Test void existingPasswordAccountsLinkGoogleOnlyAfterOtpWithConcurrentConsumption() throws Exception {
        var repositories = new JpaRepositoryFactory(em);
        var identities = repositories.getRepository(GoogleIdentityRepository.class);
        var challenges = repositories.getRepository(AuthOtpChallengeRepository.class);
        var refresh = repositories.getRepository(RefreshTokenRepository.class);
        for (RoleName role : List.of(RoleName.RENTER, RoleName.LANDLORD, RoleName.SUBLESSOR)) {
            UUID owner = UUID.randomUUID();
            insertUser(owner, role.name(), "ACTIVE");
            String subject = "synthetic-google-" + owner;
            var sender = mock(EmailOtpClient.class);
            var tokens = mock(AuthenticationTokenService.class);
            var verifier = mock(GoogleIdTokenVerifier.class);
            var sentCode = new java.util.concurrent.atomic.AtomicReference<String>();
            doAnswer(call -> { sentCode.set(call.getArgument(1)); return null; })
                    .when(sender).sendCode(anyString(), anyString(), any());
            when(verifier.verify("synthetic-google-token")).thenReturn(
                    new GoogleIdTokenVerifier.VerifiedGoogleIdentity(subject, owner + "@example.invalid", "Google Name"));
            var otp = new AuthOtpService(challenges, users, refresh,
                    new EmailOtpProperties("sender@example.invalid", Base64.getEncoder().encodeToString(new byte[32])),
                    sender, tokens, mock(PasswordEncoder.class), Clock.systemUTC(), identities);
            var google = new GoogleAuthenticationService(verifier, identities, tokens, selection, users, otp);
            UUID challenge = tx.execute(status -> google.startLogin("synthetic-google-token", role).challengeId());
            String correct = sentCode.get();
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM google_identities WHERE user_id=?", Integer.class, owner));
            verifyNoInteractions(tokens);
            String wrong = correct.equals("000000") ? "000001" : "000000";
            tx.executeWithoutResult(status -> assertThrows(AuthException.class, () -> otp.verifyLogin(challenge, wrong)));
            assertEquals(1, sql.queryForObject("SELECT attempt_count FROM auth_otp_challenges WHERE id=?", Integer.class, challenge));
            assertEquals(0, sql.queryForObject("SELECT count(*) FROM google_identities WHERE user_id=?", Integer.class, owner));

            var start = new CountDownLatch(1);
            Callable<Boolean> consume = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return tx.execute(status -> {
                    try { otp.verifyLogin(challenge, correct); return true; }
                    catch (AuthException rejected) { return false; }
                });
            };
            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(consume);
                var second = executor.submit(consume);
                start.countDown();
                assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            }
            verify(tokens, times(1)).issue(argThat(user -> user.getId().equals(owner)));
            assertEquals(subject, sql.queryForObject("SELECT google_subject FROM google_identities WHERE user_id=?", String.class, owner));
            assertEquals("synthetic-hash", sql.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, owner));
            assertEquals(role, tx.execute(status -> userRoles.findAllByUser_Id(owner).getFirst().getRole().getName()));
            // Subsequent Google login resolves the same user by immutable subject.
            tx.executeWithoutResult(status -> assertEquals(owner, google.verifyIdentity("synthetic-google-token", role).getId()));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> sql.update("INSERT INTO google_identities (user_id, google_subject) VALUES (?, ?)", landlord, subject));
        }
    }

    @Test void googleLinkAndOtpConsumptionRollbackTogetherWhenSessionIssuanceFails() {
        UUID owner = UUID.randomUUID();
        insertUser(owner, "RENTER", "ACTIVE");
        var repositories = new JpaRepositoryFactory(em);
        var sender = mock(EmailOtpClient.class);
        var tokens = mock(AuthenticationTokenService.class);
        var code = new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call -> { code.set(call.getArgument(1)); return null; })
                .when(sender).sendCode(anyString(), anyString(), any());
        var otp = new AuthOtpService(repositories.getRepository(AuthOtpChallengeRepository.class), users,
                repositories.getRepository(RefreshTokenRepository.class),
                new EmailOtpProperties("sender@example.invalid", Base64.getEncoder().encodeToString(new byte[32])),
                sender, tokens, mock(PasswordEncoder.class), Clock.systemUTC(), repositories.getRepository(GoogleIdentityRepository.class));
        UUID challenge = tx.execute(status -> otp.startGoogleLogin(users.findById(owner).orElseThrow(), "rollback-google-subject").challengeId());
        doThrow(new IllegalStateException("Synthetic session failure")).when(tokens).issue(any());
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> otp.verifyLogin(challenge, code.get())));
        assertEquals(0, sql.queryForObject("SELECT count(*) FROM google_identities WHERE user_id=?", Integer.class, owner));
        assertEquals("PENDING", sql.queryForObject("SELECT status FROM auth_otp_challenges WHERE id=?", String.class, challenge));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> sql.update("UPDATE auth_otp_challenges SET purpose='PASSWORD_RESET' WHERE id=?", challenge));
    }

    @Test void landlordReadinessQueryCannotReturnAnotherOwnersDocuments() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insertUser(first, "LANDLORD", "ACTIVE");
        insertUser(second, "LANDLORD", "ACTIVE");
        var documents = new JpaRepositoryFactory(em).getRepository(
                com.makaohub.backend.verification.repository.LandlordVerificationDocumentRepository.class);
        tx.executeWithoutResult(status -> {
            var own = new LandlordVerificationDocument(first, VerificationDocumentType.ID_FRONT,
                    "synthetic/first", "image/jpeg");
            own.markReady(100);
            documents.saveAndFlush(own);
            var other = new LandlordVerificationDocument(second, VerificationDocumentType.ID_BACK,
                    "synthetic/second", "image/jpeg");
            other.markReady(100);
            documents.saveAndFlush(other);
        });
        var service = new com.makaohub.backend.verification.service.LandlordVerificationService(
                users, userRoles, verifications, documents,
                mock(com.makaohub.backend.storage.service.SupabaseStorageClient.class),
                mock(com.makaohub.backend.storage.config.UploadSecurityProperties.class),
                mock(com.makaohub.backend.verification.service.OwnershipPdfValidator.class),
                mock(com.makaohub.backend.verification.service.VerificationImageValidator.class));
        var result = tx.execute(status -> service.getMyDocuments(first));
        assertEquals(1, result.size());
        assertEquals(VerificationDocumentType.ID_FRONT, result.getFirst().documentType());
        assertEquals(1, tx.execute(status -> service.getMyDocuments(second)).size());
        assertNotEquals(result.getFirst().documentId(),
                tx.execute(status -> service.getMyDocuments(second)).getFirst().documentId());
    }
}
