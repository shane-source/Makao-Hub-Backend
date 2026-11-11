package com.makaohub.backend.admin;

import com.makaohub.backend.auth.api.*;
import com.makaohub.backend.auth.config.*;
import com.makaohub.backend.auth.domain.*;
import com.makaohub.backend.auth.repository.*;
import com.makaohub.backend.auth.service.*;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.messaging.service.ChatContactMaskingService;
import com.makaohub.backend.storage.service.SupabaseStorageClient;
import jakarta.persistence.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@EnabledIfEnvironmentVariable(named="MAKAO_ROLE_TEST_DB_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminWorkspaceIntegrationTest {
    private final String schema="admin_test_"+UUID.randomUUID().toString().replace("-","");
    private final UUID owner=UUID.randomUUID(), oldListing=UUID.randomUUID();
    private JdbcTemplate sql;
    private AdminStore db;
    private StaffAccessService access;
    private AdminWorkflowService work;
    private StaffInvitationService invites;
    private StaffAuthService auth;
    private TransactionTemplate tx;
    private LocalContainerEntityManagerFactoryBean factory;
    private final EmailOtpClient mail=mock(EmailOtpClient.class);
    private final SupabaseStorageClient storage=mock(SupabaseStorageClient.class);
    private final Map<String,String> invitationCodes=new HashMap<>(), otpCodes=new HashMap<>();
    private final Map<String,Jwt> encoded=new ConcurrentHashMap<>();
    private final Map<String,AuthTokensResponse> issued=new ConcurrentHashMap<>();
    private Jwt lead,reviewer,safety,payment;
    private UserReportService reports;

    @BeforeAll void setup() throws Exception {
        String url=System.getenv("MAKAO_ROLE_TEST_DB_URL");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/makao_role_test"));
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_USER","hp"),
                System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_PASSWORD",""));
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("57").load().migrate();
        sql=new JdbcTemplate(source);
        sql.update("INSERT INTO users(id,email,full_name,password_hash) VALUES (?,?,'Test owner','not-a-live-password')",owner,owner+"@example.invalid");
        sql.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='LANDLORD'",owner);
        sql.update("UPDATE role_verifications SET status='VERIFIED' WHERE user_id=? AND role='LANDLORD'",owner);
        sql.update("INSERT INTO listings(id,landlord_id,title,description,property_type,monthly_rent_kes,bedroom_count,bathroom_count,status) VALUES (?,?,'Test home','Synthetic data','HOUSE',10000,1,1,'PUBLISHED')",oldListing,owner);
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        assertEquals("PAUSED",sql.queryForObject("SELECT status FROM listings WHERE id=?",String.class,oldListing));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='LISTING_APPROVAL_GATE_ENABLED'",Integer.class));
        factory=new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
        factory.setPackagesToScan("com.makaohub.backend.auth.domain"); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate")); factory.afterPropertiesSet();
        EntityManagerFactory emf=Objects.requireNonNull(factory.getObject());
        var manager=new JpaTransactionManager(emf); manager.setDataSource(source); tx=new TransactionTemplate(manager);
        EntityManager em=SharedEntityManagerCreator.createSharedEntityManager(emf);
        var repositories=new JpaRepositoryFactory(em);
        var users=repositories.getRepository(UserRepository.class);
        var roles=repositories.getRepository(RoleRepository.class);
        var userRoles=repositories.getRepository(UserRoleRepository.class);
        var refreshRows=repositories.getRepository(RefreshTokenRepository.class);
        var otpRows=repositories.getRepository(AuthOtpChallengeRepository.class);
        var properties=new JwtSecurityProperties("test","test","test-key",Duration.ofMinutes(15),Duration.ofDays(30),"unused","unused");
        JwtEncoder encoder=params -> {
            String value="synthetic-"+UUID.randomUUID();
            Jwt jwt=Jwt.withTokenValue(value).headers(h->h.putAll(params.getJwsHeader().getHeaders())).claims(c->c.putAll(params.getClaims().getClaims())).build();
            encoded.put(value,jwt); return jwt;
        };
        var refresh=proxy(new RefreshTokenService(refreshRows,properties,Clock.systemUTC()),manager);
        var tokens=proxy(new AuthenticationTokenService(userRoles,new AccessTokenService(encoder,properties,Clock.systemUTC()),refresh),manager);
        var passwords=new BCryptPasswordEncoder(4); // Tests only; application keeps cost 12.
        var normalizer=proxy(new LegacyRoleSelectionService(em,roles,userRoles),manager);
        var passwordLogin=proxy(new PasswordAuthenticationService(users,passwords,tokens,normalizer),manager);
        var emailProperties=new EmailOtpProperties("sender@example.invalid",Base64.getEncoder().encodeToString(new byte[32]));
        var otp=proxy(new AuthOtpService(otpRows,users,refreshRows,emailProperties,mail,tokens,passwords,Clock.systemUTC(),
                repositories.getRepository(GoogleIdentityRepository.class)),manager);
        db=proxy(new AdminStore(sql),manager); access=new StaffAccessService(db);
        work=proxy(new AdminWorkflowService(db,access,storage,new ChatContactMaskingService()),manager);
        reports=proxy(new UserReportService(db,access,new ChatContactMaskingService(),work),manager);
        invites=proxy(new StaffInvitationService(db,access,mail,passwords,emailProperties),manager);
        auth=proxy(new StaffAuthService(db,passwordLogin,otp,tokens,refreshRows,access,manager),manager);
        doAnswer(call->{invitationCodes.put(call.getArgument(0),call.getArgument(1)); return null;}).when(mail).sendStaffInvitation(anyString(),anyString());
        doAnswer(call->{otpCodes.put(call.getArgument(0),call.getArgument(1)); return null;}).when(mail).sendCode(anyString(),anyString(),any());
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->invites.bootstrap("lead@example.invalid"));
            var second=executor.submit(()->invites.bootstrap("lead@example.invalid"));
            assertEquals(Set.of(StaffInvitationService.BootstrapResult.INVITATION_SENT,
                    StaffInvitationService.BootstrapResult.INVITATION_PENDING),
                    Set.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)));
        }
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM staff_invitations",Integer.class));
        verify(mail,times(1)).sendStaffInvitation(eq("lead@example.invalid"),anyString());
        lead=acceptAndLogin("lead@example.invalid");
        reviewer=addStaff("reviewer@example.invalid",StaffRole.VERIFICATION_REVIEWER,true);
        safety=addStaff("safety@example.invalid",StaffRole.SAFETY_MODERATOR,false);
        payment=addStaff("payment@example.invalid",StaffRole.PAYMENT_REVIEWER,false);
    }
    @AfterAll void cleanup() { if(factory!=null) factory.destroy(); }
    private <T>T transaction(Supplier<T> operation) { return tx.execute(status->operation.get()); }
    @SuppressWarnings("unchecked")
    private static <T>T proxy(T target,org.springframework.transaction.PlatformTransactionManager manager) {
        var factory=new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        return (T)factory.getProxy();
    }
    private Jwt addStaff(String email,StaffRole role,boolean documents) {
        transaction(()->invites.invite(lead,UUID.randomUUID(),new AdminRequests.Invite(email,role,documents,"Approved test onboarding")));
        return acceptAndLogin(email);
    }
    private Jwt acceptAndLogin(String email) {
        transaction(()->invites.accept(new AdminRequests.Accept(email,invitationCodes.get(email),"Synthetic Staff","TestPassword!")));
        OtpChallengeResponse challenge=transaction(()->auth.login(new LoginRequest(email,"TestPassword!")));
        AuthTokensResponse response=transaction(()->auth.verify(new OtpVerifyRequest(challenge.challengeId().toString(),otpCodes.get(email))));
        issued.put(response.accessToken(),response);
        return encoded.get(response.accessToken());
    }

    @Test void adminRoleAloneAndOrdinaryAccountsCannotOpenStaffWorkspace() {
        Jwt noProof=Jwt.withTokenValue("no-mfa-proof").header("alg","RS256").subject(lead.getSubject())
                .claim("roles",List.of("ADMIN")).claim("authorization_version",0L).build();
        assertEquals(403,assertThrows(ResponseStatusException.class,()->transaction(()->access.require(noProof))).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->transaction(()->work.payments(reviewer,0,20))).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->transaction(()->work.verificationCase(safety,owner,"LANDLORD"))).getStatusCode().value());
    }

    @Test void staffLeadCanReviewVerificationWithoutGainingSafetyOrFinancialActions() {
        long paymentsBefore=sql.queryForObject("SELECT count(*) FROM payment_intents",Long.class);
        long bookingsBefore=sql.queryForObject("SELECT count(*) FROM bookings",Long.class);
        assertNotNull(work.verificationQueue(lead,null,"PENDING",null,0,20));
        assertNotNull(work.listings(lead,"LANDLORD","PENDING",0,20));
        assertNotNull(work.listings(lead,"SUBLESSOR","PENDING",0,20));
        var listing=work.listingDetail(lead,"LANDLORD",oldListing);
        assertFalse(listing.containsKey("exact_latitude"));
        assertFalse(listing.containsKey("exact_longitude"));
        assertFalse(listing.containsKey("address"));
        assertNotNull(work.flags(lead,0,20));
        assertNotNull(reports.queue(lead,"OPEN",0,20));
        assertNotNull(work.payments(lead,0,20));
        assertNotNull(work.bookings(lead,0,20));
        assertNotNull(work.audit(lead,null,null,0,20));
        assertNotNull(work.staff(lead,0,20));
        assertNotNull(work.activity(lead,0,20));
        assertEquals(paymentsBefore,sql.queryForObject("SELECT count(*) FROM payment_intents",Long.class));
        assertEquals(bookingsBefore,sql.queryForObject("SELECT count(*) FROM bookings",Long.class));
        assertNotNull(work.verificationCase(lead,owner,"LANDLORD"));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->work.flagAction(lead,UUID.randomUUID(),"RENTAL",UUID.randomUUID(),
                new AdminRequests.MessageAction(false,"Not authorised"))).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->reports.act(lead,UUID.randomUUID(),UUID.randomUUID(),
                new AdminRequests.ReportAction("RESOLVED","NORMAL",false,false,"Not authorised"))).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->work.flags(payment,0,20)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->work.payments(safety,0,20)).getStatusCode().value());
    }

    @SuppressWarnings("unchecked")
    @Test void staffLeadCanReviewDocumentsAndApproveWithoutAssignment() {
        UUID applicant=UUID.randomUUID();
        sql.update("INSERT INTO users(id,email,full_name,password_hash) VALUES (?,?,'Lead-reviewed renter','unused')",applicant,applicant+"@example.invalid");
        sql.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='RENTER'",applicant);
        sql.update("UPDATE role_verifications SET status='PENDING',submitted_at=CURRENT_TIMESTAMP WHERE user_id=? AND role='RENTER'",applicant);
        List<UUID> documents=new ArrayList<>();
        for(String type:List.of("ID_FRONT","ID_BACK","SELFIE")) {
            UUID document=UUID.randomUUID(); documents.add(document);
            sql.update("INSERT INTO renter_verification_documents(id,renter_id,document_type,object_path,content_type,size_bytes,status) VALUES (?,?,?,?,'image/jpeg',3,'READY')",
                    document,applicant,type,applicant+"/renter-evidence-v1/"+document+".jpg");
        }
        when(storage.readVerificationDocument(anyString(),eq(3L))).thenReturn(new byte[]{1,2,3});
        Instant version=(Instant)transaction(()->work.verificationCase(lead,applicant,"RENTER")).get("updated_at");
        for(UUID document:documents) {
            transaction(()->work.document(lead,applicant,"RENTER",document));
            transaction(()->work.markDocumentReviewed(lead,UUID.randomUUID(),applicant,"RENTER",document));
        }
        assertEquals("VERIFIED",transaction(()->work.decideVerification(lead,UUID.randomUUID(),applicant,"RENTER",
                new AdminRequests.Decision(true,version,"Staff lead reviewed all current evidence"))).get("state"));
        assertEquals(3,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='PRIVATE_DOCUMENT_VIEWED' AND actor_id=? AND subject_id IN (?,?,?)",Integer.class,
                UUID.fromString(lead.getSubject()),documents.get(0),documents.get(1),documents.get(2)));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='ROLE_VERIFICATION_DECIDED' AND actor_id=? AND subject_id=?",Integer.class,UUID.fromString(lead.getSubject()),applicant));
    }

    @Test void rejectedLandlordDecisionStoresTheOwnersOutcomeAndNotifiesOnlyOnce() {
        UUID applicant=UUID.randomUUID();
        sql.update("INSERT INTO users(id,email,full_name,password_hash) VALUES (?,?,'Synthetic landlord','unused')",applicant,applicant+"@example.invalid");
        sql.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='LANDLORD'",applicant);
        sql.update("UPDATE role_verifications SET status='PENDING',submitted_at=CURRENT_TIMESTAMP WHERE user_id=? AND role='LANDLORD'",applicant);
        work.assign(lead,UUID.randomUUID(),applicant,"LANDLORD",new AdminRequests.Assign(UUID.fromString(reviewer.getSubject()),"Assigned test case"));
        var version=(Instant)db.one("SELECT updated_at FROM role_verifications WHERE user_id=? AND role='LANDLORD'",applicant).get("updated_at");
        UUID command=UUID.randomUUID();
        var decision=new AdminRequests.Decision(false,version,"Ownership proof is unreadable");
        var response=work.decideVerification(reviewer,command,applicant,"LANDLORD",decision);
        work.decideVerification(reviewer,command,applicant,"LANDLORD",decision);
        assertEquals("REJECTED",response.get("state"));
        var outcome=db.one("SELECT status,reviewed_at,rejection_reason FROM role_verifications WHERE user_id=? AND role='LANDLORD'",applicant);
        assertEquals("REJECTED",outcome.get("status"));
        assertNotNull(outcome.get("reviewed_at"));
        assertEquals("Ownership proof is unreadable",outcome.get("rejection_reason"));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND type='VERIFICATION_DECISION'",Integer.class,applicant));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE subject_id=? AND action='ROLE_VERIFICATION_DECIDED'",Integer.class,applicant));
    }

    @Test void pendingPublicationIsBlockedEvenThroughDirectDatabaseWrites() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->transaction(()->sql.update("UPDATE listings SET status='PUBLISHED' WHERE id=?",oldListing)));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM listings WHERE status='PUBLISHED'",Integer.class));
    }

    @Test void invitationCannotPromoteExistingAccountOrBeAcceptedTwice() {
        assertThrows(ResponseStatusException.class,()->transaction(()->invites.invite(lead,UUID.randomUUID(),new AdminRequests.Invite(owner+"@example.invalid",StaffRole.SAFETY_MODERATOR,false,"Test"))));
        assertThrows(ResponseStatusException.class,()->transaction(()->invites.accept(new AdminRequests.Accept("reviewer@example.invalid",invitationCodes.get("reviewer@example.invalid"),"Test","TestPassword!"))));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM user_roles WHERE user_id=?",Integer.class,UUID.fromString(reviewer.getSubject())));
    }

    @Test void restartingBootstrapAfterAcceptanceDoesNotAddInvitationsOrStaff() {
        int invitationsBefore=sql.queryForObject("SELECT count(*) FROM staff_invitations",Integer.class);
        int staffBefore=sql.queryForObject("SELECT count(*) FROM staff_accounts",Integer.class);
        assertEquals(StaffInvitationService.BootstrapResult.STAFF_EXISTS,invites.bootstrap("new@example.invalid"));
        assertEquals(invitationsBefore,sql.queryForObject("SELECT count(*) FROM staff_invitations",Integer.class));
        assertEquals(staffBefore,sql.queryForObject("SELECT count(*) FROM staff_accounts",Integer.class));
        verify(mail,never()).sendStaffInvitation(eq("new@example.invalid"),anyString());
    }

    @Test void wrongOtpAttemptsPersistAndLockAfterFiveFailures() {
        var person=addStaff("locked@example.invalid",StaffRole.PAYMENT_REVIEWER,false);
        var challenge=transaction(()->auth.login(new LoginRequest("locked@example.invalid","TestPassword!")));
        for(int attempt=0;attempt<5;attempt++) transaction(()->{
            String wrong=otpCodes.get("locked@example.invalid").equals("000000")?"111111":"000000";
            assertThrows(AuthException.class,()->auth.verify(new OtpVerifyRequest(challenge.challengeId().toString(),wrong)));
            return null;
        });
        assertEquals("LOCKED",sql.queryForObject("SELECT status FROM auth_otp_challenges WHERE id=?",String.class,challenge.challengeId()));
        assertEquals(5,sql.queryForObject("SELECT attempt_count FROM auth_otp_challenges WHERE id=?",Integer.class,challenge.challengeId()));
        assertNotNull(person);
    }

    @Test void assignedReviewerMustViewAndAcknowledgeEveryCurrentDocumentBeforeApproval() {
        UUID applicant=UUID.randomUUID();
        sql.update("INSERT INTO users(id,email,full_name,password_hash) VALUES (?,?,'Synthetic renter','unused')",applicant,applicant+"@example.invalid");
        sql.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='RENTER'",applicant);
        sql.update("UPDATE role_verifications SET status='PENDING',submitted_at=CURRENT_TIMESTAMP WHERE user_id=? AND role='RENTER'",applicant);
        List<UUID> documents=new ArrayList<>();
        for(String type:List.of("ID_FRONT","ID_BACK","SELFIE")) {
            UUID id=UUID.randomUUID(); documents.add(id);
            sql.update("INSERT INTO renter_verification_documents(id,renter_id,document_type,object_path,content_type,size_bytes,status) VALUES (?,?,?,?,'image/jpeg',3,'READY')",id,applicant,type,applicant+"/renter-evidence-v1/"+id+".jpg");
        }
        assertThrows(ResponseStatusException.class,()->transaction(()->work.verificationCase(reviewer,applicant,"RENTER")));
        transaction(()->work.assign(lead,UUID.randomUUID(),applicant,"RENTER",new AdminRequests.Assign(UUID.fromString(reviewer.getSubject()),"Assigned test review")));
        Instant version=(Instant)transaction(()->work.verificationCase(reviewer,applicant,"RENTER")).get("updated_at");
        var decision=new AdminRequests.Decision(true,version,"Required current evidence reviewed");
        assertThrows(ResponseStatusException.class,()->transaction(()->work.decideVerification(reviewer,UUID.randomUUID(),applicant,"RENTER",decision)));
        when(storage.readVerificationDocument(anyString(),eq(3L))).thenReturn(new byte[]{1,2,3});
        for(UUID document:documents) {
            var before=(List<Map<String,Object>>)transaction(()->work.verificationCase(reviewer,applicant,"RENTER")).get("documents");
            assertNull(before.stream().filter(d->document.equals(d.get("id"))).findFirst().orElseThrow().get("viewed_at"));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->transaction(()->work.markDocumentReviewed(
                    reviewer,UUID.randomUUID(),applicant,"RENTER",document))).getStatusCode().value());
            transaction(()->work.document(reviewer,applicant,"RENTER",document));
            var viewed=(List<Map<String,Object>>)transaction(()->work.verificationCase(reviewer,applicant,"RENTER")).get("documents");
            assertNotNull(viewed.stream().filter(d->document.equals(d.get("id"))).findFirst().orElseThrow().get("viewed_at"));
            transaction(()->work.markDocumentReviewed(reviewer,UUID.randomUUID(),applicant,"RENTER",document));
        }
        UUID changed=documents.getFirst();
        sql.update("UPDATE renter_verification_documents SET updated_at=CURRENT_TIMESTAMP WHERE id=?",changed);
        var replaced=(List<Map<String,Object>>)transaction(()->work.verificationCase(reviewer,applicant,"RENTER")).get("documents");
        var current=replaced.stream().filter(d->changed.equals(d.get("id"))).findFirst().orElseThrow();
        assertNull(current.get("viewed_at"));
        assertNull(current.get("reviewed_at"));
        assertThrows(ResponseStatusException.class,()->transaction(()->work.markDocumentReviewed(reviewer,UUID.randomUUID(),applicant,"RENTER",changed)));
        assertThrows(ResponseStatusException.class,()->transaction(()->work.decideVerification(reviewer,UUID.randomUUID(),applicant,"RENTER",decision)));
        transaction(()->work.document(reviewer,applicant,"RENTER",changed));
        transaction(()->work.markDocumentReviewed(reviewer,UUID.randomUUID(),applicant,"RENTER",changed));
        UUID command=UUID.randomUUID();
        transaction(()->work.decideVerification(reviewer,command,applicant,"RENTER",decision));
        transaction(()->work.decideVerification(reviewer,command,applicant,"RENTER",decision));
        assertEquals("VERIFIED",sql.queryForObject("SELECT status FROM role_verifications WHERE user_id=? AND role='RENTER'",String.class,applicant));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='ROLE_VERIFICATION_DECIDED' AND subject_id=?",Integer.class,applicant));
    }

    @Test void auditIsAppendOnlyAndBoundedAndPaymentReviewDoesNotMutateMoney() {
        assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("UPDATE admin_audit_events SET reason='tamper'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->sql.execute("TRUNCATE admin_audit_events"));
        assertThrows(ResponseStatusException.class,()->transaction(()->work.audit(lead,null,null,0,51)));
        assertNotNull(transaction(()->work.payments(payment,0,20)));
        assertNotNull(transaction(()->work.bookings(payment,0,20)));
        assertNotNull(transaction(()->work.flags(safety,0,20)));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM payment_intents",Integer.class));
    }

    @Test void revokedStaffCannotContinueUsingExistingAccessTokens() {
        Jwt target=addStaff("disabled@example.invalid",StaffRole.PAYMENT_REVIEWER,false);
        transaction(()->invites.change(lead,UUID.randomUUID(),UUID.fromString(target.getSubject()),new AdminRequests.StaffChange(StaffRole.PAYMENT_REVIEWER,false,false,"Disabled test access")));
        assertThrows(ResponseStatusException.class,()->transaction(()->access.require(target)));
        assertThrows(ResponseStatusException.class,()->transaction(()->invites.change(lead,UUID.randomUUID(),UUID.fromString(lead.getSubject()),new AdminRequests.StaffChange(StaffRole.PAYMENT_REVIEWER,false,false,"Self demotion blocked"))));
    }

    @Test void refreshReplayRevokesStaffAccessAndLogoutInvalidatesTheWholeSession() {
        Jwt member=addStaff("rotate@example.invalid",StaffRole.PAYMENT_REVIEWER,false);
        String previous=issued.get(member.getTokenValue()).refreshToken();
        var response=auth.refresh(previous);
        Jwt replacement=encoded.get(response.accessToken());
        assertNotNull(transaction(()->access.require(replacement)));
        assertThrows(AuthException.class,()->auth.refresh(previous));
        assertThrows(ResponseStatusException.class,()->transaction(()->access.require(replacement)));
        Jwt logout=addStaff("logout@example.invalid",StaffRole.PAYMENT_REVIEWER,false);
        auth.logout(logout);
        assertThrows(ResponseStatusException.class,()->transaction(()->access.require(logout)));
    }

    @Test void failedPasswordIsAuditedWithoutRollingBackOrReturningAServerError() {
        long count=sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='STAFF_LOGIN_FAILED'",Long.class);
        assertThrows(AuthException.class,()->auth.login(new LoginRequest("lead@example.invalid","wrong-password")));
        assertEquals(count+1,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='STAFF_LOGIN_FAILED'",Long.class));
    }

    @Test void listingApprovalRestoresOldPublicationAndEvidenceEditsRemoveApproval() throws Exception {
        UUID listing=completeListing();
        Instant version=(Instant)db.one("SELECT updated_at FROM listings WHERE id=?",listing).get("updated_at");
        UUID key=UUID.randomUUID();
        var decision=new AdminRequests.Decision(true,version,"Media and description reviewed");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->work.decideListing(safety,key,"LANDLORD",listing,decision));
            var second=executor.submit(()->work.decideListing(safety,key,"LANDLORD",listing,decision));
            assertEquals("APPROVED",first.get(10,TimeUnit.SECONDS).get("moderationStatus"));
            assertEquals("APPROVED",second.get(10,TimeUnit.SECONDS).get("moderationStatus"));
        }
        assertEquals("PUBLISHED",sql.queryForObject("SELECT status FROM listings WHERE id=?",String.class,listing));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='LISTING_MODERATION_DECIDED' AND subject_id=?",Integer.class,listing));
        sql.update("UPDATE listing_media SET sort_order=1 WHERE listing_id=?",listing);
        assertEquals("PAUSED",sql.queryForObject("SELECT status FROM listings WHERE id=?",String.class,listing));
        assertEquals("PENDING",sql.queryForObject("SELECT moderation_status FROM listings WHERE id=?",String.class,listing));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE listings SET status='PUBLISHED' WHERE id=?",listing));
    }

    @Test void staffLeadCanApproveNewLandlordListingAndOwnerCanPublish() {
        UUID listing=UUID.randomUUID();
        sql.update("INSERT INTO listings(id,landlord_id,title,description,property_type,monthly_rent_kes,bedroom_count,bathroom_count,status) VALUES (?,?,'Lead reviewed home','Synthetic data','HOUSE',10000,1,1,'DRAFT')",listing,owner);
        sql.update("INSERT INTO listing_locations(listing_id,area_label,exact_coordinates_ciphertext,approximate_latitude,approximate_longitude,approximate_radius_meters) VALUES (?,'Test area',?,0,36,500)",listing,new byte[]{1});
        sql.update("INSERT INTO listing_media(listing_id,media_type,object_path,content_type,size_bytes,confirmed_at) VALUES (?,'IMAGE',?,'image/jpeg',3,CURRENT_TIMESTAMP)",listing,listing+"/synthetic.jpg");
        Jwt ownerToken=Jwt.withTokenValue("synthetic-owner").header("alg","RS256").subject(owner.toString()).claim("roles",List.of("LANDLORD")).build();

        work.submitListing(ownerToken,"LANDLORD",listing);
        Instant version=(Instant)db.one("SELECT updated_at FROM listings WHERE id=?",listing).get("updated_at");
        assertEquals("APPROVED",work.decideListing(lead,UUID.randomUUID(),"LANDLORD",listing,
                new AdminRequests.Decision(true,version,"Staff lead reviewed media and claims")).get("moderationStatus"));
        assertEquals("DRAFT",sql.queryForObject("SELECT status FROM listings WHERE id=?",String.class,listing));
        assertEquals(1,sql.update("UPDATE listings SET status='PUBLISHED' WHERE id=?",listing));
        assertEquals("PUBLISHED",sql.queryForObject("SELECT status FROM listings WHERE id=?",String.class,listing));
    }

    @SuppressWarnings("unchecked")
    @Test void listingMediaReadIsPermissionedAuditedAndContainsNoStorageCapability() {
        UUID listing=completeListing();
        var row=db.one("SELECT id,object_path,size_bytes FROM listing_media WHERE listing_id=?",listing);
        UUID media=(UUID)row.get("id");
        when(storage.readListingMedia((String)row.get("object_path"),((Number)row.get("size_bytes")).longValue()))
                .thenReturn(new byte[]{1,2,3});

        var detail=work.listingDetail(lead,"LANDLORD",listing);
        var metadata=((List<Map<String,Object>>)detail.get("media")).getFirst();
        assertFalse(metadata.containsKey("object_path"));
        assertFalse(metadata.containsKey("readUrl"));
        assertArrayEquals(new byte[]{1,2,3},work.listingMedia(lead,"LANDLORD",listing,media).bytes());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='LISTING_MEDIA_VIEWED' AND subject_id=?",Integer.class,media));
        assertEquals(403,assertThrows(ResponseStatusException.class,
                ()->work.listingMedia(reviewer,"LANDLORD",listing,media)).getStatusCode().value());
    }

    private UUID completeListing() {
        UUID listing=UUID.randomUUID();
        sql.update("INSERT INTO listings(id,landlord_id,title,description,property_type,monthly_rent_kes,bedroom_count,bathroom_count,status) VALUES (?,?,'Test home','Synthetic data','HOUSE',10000,1,1,'PAUSED')",listing,owner);
        sql.update("INSERT INTO listing_locations(listing_id,area_label,exact_coordinates_ciphertext,approximate_latitude,approximate_longitude,approximate_radius_meters) VALUES (?,'Test area',?,0,36,500)",listing,new byte[]{1});
        sql.update("INSERT INTO listing_media(listing_id,media_type,object_path,content_type,size_bytes,confirmed_at) VALUES (?,'IMAGE',?,'image/jpeg',3,CURRENT_TIMESTAMP)",listing,listing+"/synthetic.jpg");
        sql.update("UPDATE listings SET restore_publication_after_review=TRUE,moderation_submitted_at=CURRENT_TIMESTAMP WHERE id=?",listing);
        return listing;
    }

    @Test void reportResolutionHidesTheListingAndNeverRevealsReporterInStaffQueue() {
        UUID listing=completeListing();
        Instant version=(Instant)db.one("SELECT updated_at FROM listings WHERE id=?",listing).get("updated_at");
        work.decideListing(safety,UUID.randomUUID(),"LANDLORD",listing,new AdminRequests.Decision(true,version,"Reviewed current listing"));
        Jwt ownerToken=Jwt.withTokenValue("synthetic-owner").header("alg","RS256").subject(owner.toString()).claim("roles",List.of("LANDLORD")).build();
        var report=reports.create(ownerToken,new AdminRequests.Report("LANDLORD_LISTING",listing,"MISLEADING_CONTENT","The photographs do not match the home"));
        UUID id=(UUID)report.get("reportId");
        assertTrue(reports.queue(safety,"OPEN",0,20).items().stream().noneMatch(row->row.containsKey("reporter_id")));
        reports.act(safety,UUID.randomUUID(),id,new AdminRequests.ReportAction("RESOLVED","NORMAL",true,true,"Listing imagery needs correction"));
        assertEquals("PAUSED",sql.queryForObject("SELECT status FROM listings WHERE id=?",String.class,listing));
        assertEquals("REJECTED",sql.queryForObject("SELECT moderation_status FROM listings WHERE id=?",String.class,listing));
        assertThrows(ResponseStatusException.class,()->reports.act(safety,UUID.randomUUID(),id,new AdminRequests.ReportAction("INVESTIGATING","NORMAL",false,false,"Cannot reopen terminal record")));
        work.reviseListing(ownerToken,"LANDLORD",listing);
        assertEquals("DRAFT",sql.queryForObject("SELECT status FROM listings WHERE id=?",String.class,listing));
    }

    @Test void rejectedNewListingCanReturnToAnEditableUnsubmittedDraft() {
        UUID listing=UUID.randomUUID();
        sql.update("INSERT INTO listings(id,landlord_id,title,description,property_type,monthly_rent_kes,bedroom_count,bathroom_count,status) VALUES (?,?,'New test home','Synthetic data','HOUSE',10000,1,1,'DRAFT')",listing,owner);
        sql.update("INSERT INTO listing_locations(listing_id,area_label,exact_coordinates_ciphertext,approximate_latitude,approximate_longitude,approximate_radius_meters) VALUES (?,'Test area',?,0,36,500)",listing,new byte[]{1});
        sql.update("INSERT INTO listing_media(listing_id,media_type,object_path,content_type,size_bytes,confirmed_at) VALUES (?,'IMAGE',?,'image/jpeg',3,CURRENT_TIMESTAMP)",listing,listing+"/synthetic.jpg");
        Jwt ownerToken=Jwt.withTokenValue("synthetic-owner").header("alg","RS256").subject(owner.toString()).claim("roles",List.of("LANDLORD")).build();

        work.submitListing(ownerToken,"LANDLORD",listing);
        Instant version=(Instant)db.one("SELECT updated_at FROM listings WHERE id=?",listing).get("updated_at");
        work.decideListing(lead,UUID.randomUUID(),"LANDLORD",listing,new AdminRequests.Decision(false,version,"Description needs correction"));
        assertEquals("DRAFT",sql.queryForObject("SELECT status FROM listings WHERE id=?",String.class,listing));
        assertEquals("REJECTED",sql.queryForObject("SELECT moderation_status FROM listings WHERE id=?",String.class,listing));

        work.reviseListing(ownerToken,"LANDLORD",listing);
        assertEquals("DRAFT",sql.queryForObject("SELECT status FROM listings WHERE id=?",String.class,listing));
        assertEquals("PENDING",sql.queryForObject("SELECT moderation_status FROM listings WHERE id=?",String.class,listing));
        assertNull(sql.queryForObject("SELECT moderation_submitted_at FROM listings WHERE id=?",Instant.class,listing));
    }

    @Test void auditExportIsPermissionedBoundedAndReplayDoesNotCreateAnotherExport() {
        UUID key=UUID.randomUUID();
        var request=new AdminRequests.AuditExport(null,null,0,10,"Operational review");
        var first=work.export(lead,key,request);
        var again=work.export(lead,key,request);
        assertEquals(10,((List<?>)first.get("items")).size());
        assertEquals(10,((List<?>)again.get("items")).size());
        assertThrows(ResponseStatusException.class,()->work.export(payment,UUID.randomUUID(),request));
        assertThrows(ResponseStatusException.class,()->work.export(lead,key,new AdminRequests.AuditExport(null,null,0,9,"Different request")));
    }

    @Test void sharedListingsRequireIndependentApprovalAndEditsInvalidateIt() {
        UUID host=UUID.randomUUID(), listing=UUID.randomUUID();
        sql.update("INSERT INTO users(id,email,full_name,password_hash) VALUES (?,?,'Synthetic host','unused')",host,host+"@example.invalid");
        sql.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name='SUBLESSOR'",host);
        sql.update("UPDATE role_verifications SET status='VERIFIED' WHERE user_id=? AND role='SUBLESSOR'",host);
        sql.update("INSERT INTO sublessor_listings(id,sublessor_id,title,description,space_type,monthly_rent_kes) VALUES (?,?,'Synthetic room','Synthetic data','SPARE_ROOM',18000)",listing,host);
        sql.update("INSERT INTO sublessor_listing_locations(sublessor_listing_id,area_label,exact_coordinates_ciphertext,approximate_latitude,approximate_longitude,approximate_radius_meters) VALUES (?,'Synthetic area',?,0,36,500)",listing,new byte[]{1});
        sql.update("INSERT INTO sublessor_listing_media(sublessor_listing_id,media_type,object_path,content_type,size_bytes,confirmed_at) VALUES (?,'IMAGE',?,'image/jpeg',3,CURRENT_TIMESTAMP)",listing,listing+"/test.jpg");
        Jwt hostToken=Jwt.withTokenValue("synthetic-host").header("alg","RS256").subject(host.toString()).claim("roles",List.of("SUBLESSOR")).build();
        work.submitListing(hostToken,"SUBLESSOR",listing);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->sql.update("UPDATE sublessor_listings SET status='PUBLISHED' WHERE id=?",listing));
        Instant version=(Instant)db.one("SELECT updated_at FROM sublessor_listings WHERE id=?",listing).get("updated_at");
        work.decideListing(lead,UUID.randomUUID(),"SUBLESSOR",listing,new AdminRequests.Decision(true,version,"Shared space reviewed"));
        sql.update("UPDATE sublessor_listings SET status='PUBLISHED' WHERE id=?",listing);
        sql.update("UPDATE sublessor_listing_locations SET area_label='Updated synthetic area' WHERE sublessor_listing_id=?",listing);
        assertEquals("PAUSED",sql.queryForObject("SELECT status FROM sublessor_listings WHERE id=?",String.class,listing));
        assertEquals("PENDING",sql.queryForObject("SELECT moderation_status FROM sublessor_listings WHERE id=?",String.class,listing));
    }
}
