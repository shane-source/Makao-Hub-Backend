package com.makaohub.backend.renter.service;

import com.makaohub.backend.admin.AdminStore;
import com.makaohub.backend.auth.repository.*;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.renter.domain.*;
import com.makaohub.backend.renter.repository.BookingRepository;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.payment.audit.repository.PaymentAuditEventRepository;
import com.makaohub.backend.payment.audit.service.MockPaymentAuditService;
import com.makaohub.backend.payment.ledger.repository.LedgerEntryRepository;
import com.makaohub.backend.payment.ledger.service.MockEscrowLedgerService;
import com.makaohub.backend.payment.paystack.client.PaystackTestTransferClient;
import com.makaohub.backend.payment.paystack.client.PaystackTestTransferClient.*;
import com.makaohub.backend.payment.paystack.service.BookingTestPayoutService;
import com.makaohub.backend.notification.repository.NotificationRepository;
import com.makaohub.backend.notification.service.NotificationService;
import com.makaohub.backend.verification.repository.RoleVerificationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real isolated PostgreSQL/JPA/ledger/notification path; all provider calls are mocked. */
@EnabledIfEnvironmentVariable(named="MAKAO_ROLE_TEST_DB_URL",matches=".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerFirstMoveInIntegrationTest {
    private final String schema="owner_first_"+UUID.randomUUID().toString().replace("-","");
    private JdbcTemplate sql;
    private LocalContainerEntityManagerFactoryBean factory;
    private TransactionTemplate tx;
    private BookingRepository bookings;
    private PaymentIntentRepository payments;
    private BookingAgreementService agreements;
    private MockEscrowLedgerService ledger;
    private NotificationService notifications;
    private BookingTestPayoutService payouts;
    private final PaystackTestTransferClient provider=mock(PaystackTestTransferClient.class);
    @BeforeAll void database() {
        String url=System.getenv("MAKAO_ROLE_TEST_DB_URL");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/makao_role_test"));
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_USER","hp"),"");
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate(); sql=new JdbcTemplate(source);
        factory=new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(source);
        factory.setPackagesToScan("com.makaohub.backend.auth.domain","com.makaohub.backend.renter.domain",
                "com.makaohub.backend.payment.domain","com.makaohub.backend.payment.audit.domain",
                "com.makaohub.backend.payment.ledger.domain","com.makaohub.backend.notification.domain","com.makaohub.backend.verification.domain");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter()); factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate")); factory.afterPropertiesSet();
        var manager=new JpaTransactionManager(Objects.requireNonNull(factory.getObject())); tx=new TransactionTemplate(manager);
        var repositories=new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
        bookings=repositories.getRepository(BookingRepository.class); payments=repositories.getRepository(PaymentIntentRepository.class);
        var users=repositories.getRepository(UserRepository.class); var roles=repositories.getRepository(UserRoleRepository.class);
        var verifications=repositories.getRepository(RoleVerificationRepository.class);
        var audit=new MockPaymentAuditService(repositories.getRepository(PaymentAuditEventRepository.class));
        ledger=new MockEscrowLedgerService(repositories.getRepository(LedgerEntryRepository.class),audit);
        notifications=new NotificationService(users,repositories.getRepository(NotificationRepository.class));
        agreements=new BookingAgreementService(bookings,payments,users,roles,verifications,ledger,audit,notifications);
        payouts=new BookingTestPayoutService(sql,bookings,payments,ledger,notifications,provider,manager,new AdminStore(sql),true);
    }
    @BeforeEach void resetProvider() { reset(provider); }
    private record Fixture(UUID id,UUID owner,UUID renter,UUID payment,RoleName role) { }
    private UUID user(String role) {
        UUID id=UUID.randomUUID(); sql.update("INSERT INTO users(id,email,full_name) VALUES (?,?,'Synthetic test user')",id,id+"@example.invalid");
        sql.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?",id,role);
        sql.update("UPDATE role_verifications SET status='VERIFIED' WHERE user_id=?",id); return id;
    }
    private Fixture held(RoleName role) {
        UUID id=UUID.randomUUID(),owner=user(role.name()),renter=user("RENTER"),payment=UUID.randomUUID();
        sql.update("""
            INSERT INTO bookings(id,renter_id,owner_id,listing_id,listing_kind,source_application_id,
                rent_amount_minor,deposit_amount_minor,commission_amount_minor,total_amount_minor,currency,status)
            VALUES (?,?,?,?,?,?,4000000,4000000,400000,8400000,'KES','PAID_HELD')
            """,id,renter,owner,UUID.randomUUID(),role==RoleName.LANDLORD?"LANDLORD_LISTING":"SUBLESSOR_LISTING",UUID.randomUUID());
        sql.update("""
            INSERT INTO payment_intents(id,payer_user_id,payment_type,booking_id,target_kind,amount_minor,currency,idempotency_key,status,provider_reference,completed_at)
            VALUES (?,?,'BOOKING',?,'BOOKING',8400000,'KES',?,'SUCCEEDED',?,CURRENT_TIMESTAMP)
            """,payment,renter,id,UUID.randomUUID().toString(),"MH-"+payment);
        tx.executeWithoutResult(status->ledger.recordBookingHold(payments.findById(payment).orElseThrow(),bookings.findById(id).orElseThrow()));
        return new Fixture(id,owner,renter,payment,role);
    }
    private void oldReport(Fixture f) { tx.executeWithoutResult(status->{ var b=bookings.findByIdForUpdate(f.id()).orElseThrow(); b.reportOwnerMoveIn(Instant.parse("2000-01-01T00:00:00Z")); bookings.saveAndFlush(b); }); }
    private void dueTrue(Fixture f) { oldReport(f); tx.executeWithoutResult(status->{var b=bookings.findByIdForUpdate(f.id()).orElseThrow();b.respondToOwnerMoveIn(true,Instant.parse("2000-01-01T00:01:00Z"),"fresh-response");bookings.saveAndFlush(b);}); }
    private void destination(Fixture f) { sql.update("INSERT INTO paystack_test_destinations(owner_id,owner_role,recipient_id,recipient_fingerprint,configured_by) VALUES (?,?,123,?,?)",f.owner(),f.role().name(),"fingerprint",f.owner()); }
    @Test void verifiedOwnerCanBindOwnTestRecipientOnceWithoutStaffAndCannotReplaceIt() {
        for(RoleName role:List.of(RoleName.LANDLORD,RoleName.SUBLESSOR)) {
            UUID owner=user(role.name());
            when(provider.createMpesaRecipient("Synthetic test user","0712345678"))
                .thenReturn(new Recipient(123,"RCP_synthetic","fingerprint"));
            UUID key=UUID.randomUUID();
            assertEquals(true,payouts.configureOwnMpesa(owner,key,"0712345678").get("configured"));
            assertEquals(true,payouts.configureOwnMpesa(owner,key,"0712345678").get("configured"));
            assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->payouts.configureOwnMpesa(owner,UUID.randomUUID(),"0799999999"));
            assertEquals(owner,sql.queryForObject("SELECT configured_by FROM paystack_test_destinations WHERE owner_id=?",UUID.class,owner));
            verify(provider,times(1)).createMpesaRecipient("Synthetic test user","0712345678");
            clearInvocations(provider);
        }
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            ()->payouts.configureOwnMpesa(user("RENTER"),UUID.randomUUID(),"0712345678"));
    }
    private void providerSuccess() {
        when(provider.recipient("123")).thenReturn(new Recipient(123,"RCP_synthetic","fingerprint"));
        when(provider.verify(anyString())).thenReturn(new Transfer("success",8000000,123,"fingerprint"));
    }
    @Test void ownerReportsFirstAndRenterAnswerIsOwnedFreshAndImmutable() {
        for(RoleName role:List.of(RoleName.LANDLORD,RoleName.SUBLESSOR)) {
            Fixture f=held(role);
            assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->agreements.respondToMoveIn(f.owner(),f.id(),true,"wrong-role")));
            assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->agreements.respondToMoveIn(f.renter(),f.id(),true,"before-report")));
            UUID otherOwner=user(role.name());
            assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->agreements.confirmOwnerMoveIn(otherOwner,f.id(),role,"other-owner")));
            tx.executeWithoutResult(s->agreements.confirmOwnerMoveIn(f.owner(),f.id(),role,"owner-report"));
            tx.executeWithoutResult(s->agreements.confirmOwnerMoveIn(f.owner(),f.id(),role,"owner-report"));
            assertEquals(1,sql.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND booking_id=? AND type='MOVE_IN_REPORTED'",Integer.class,f.renter(),f.id()));
            assertEquals(true,sql.queryForObject("SELECT renter_agreed_at IS NULL AND owner_agreed_at IS NULL FROM bookings WHERE id=?",Boolean.class,f.id()));
            assertNull(sql.queryForObject("SELECT release_eligible_at FROM bookings WHERE id=?",java.sql.Timestamp.class,f.id()));
            UUID other=user("RENTER");
            assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->agreements.respondToMoveIn(other,f.id(),true,"other-user")));
            tx.executeWithoutResult(s->agreements.respondToMoveIn(f.renter(),f.id(),true,"true-answer"));
            tx.executeWithoutResult(s->agreements.respondToMoveIn(f.renter(),f.id(),true,"retry-answer"));
            assertEquals(true,sql.queryForObject("SELECT release_eligible_at=move_in_answered_at+INTERVAL '5 minutes' FROM bookings WHERE id=?",Boolean.class,f.id()));
            assertThrows(RuntimeException.class,()->tx.executeWithoutResult(s->agreements.respondToMoveIn(f.renter(),f.id(),false,"change-answer")));
            assertThrows(org.springframework.dao.DataAccessException.class,()->sql.update("UPDATE bookings SET move_in_accepted=FALSE WHERE id=?",f.id()));
            payouts.tick(); verify(provider,never()).initiate(anyString(),anyLong(),any());
        }
    }
    @Test void falseNoReplyAndLegacyConfirmationNeverQueueMoney() {
        Fixture noReply=held(RoleName.LANDLORD);oldReport(noReply);destination(noReply);
        Fixture rejected=held(RoleName.SUBLESSOR);oldReport(rejected);destination(rejected);
        tx.executeWithoutResult(s->agreements.respondToMoveIn(rejected.renter(),rejected.id(),false,"false-answer"));
        payouts.tick();
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM paystack_test_transfers WHERE booking_id IN (?,?)",Integer.class,noReply.id(),rejected.id()));
        assertEquals("REVIEW_REQUIRED",sql.queryForObject("SELECT test_payout_status FROM bookings WHERE id=?",String.class,rejected.id()));
        verify(provider,never()).initiate(anyString(),anyLong(),any());
    }
    @Test void successfulProviderTransferReleasesOnlyRentAndDepositOnceAndRecognizesCommissionSeparately() throws Exception {
        Fixture f=held(RoleName.LANDLORD);dueTrue(f);destination(f);providerSuccess();
        var pool=Executors.newFixedThreadPool(2);
        try { var one=pool.submit(payouts::tick);var two=pool.submit(payouts::tick);one.get(10,TimeUnit.SECONDS);two.get(10,TimeUnit.SECONDS); }
        finally {pool.shutdownNow();}
        payouts.reconcileReference("mh-owner-"+f.id());payouts.tick();
        verify(provider,times(1)).initiate(eq("mh-owner-"+f.id()),eq(8000000L),any());
        verify(provider).verifyCollection("MH-"+f.payment(),8400000L);
        assertEquals("ACTIVE",sql.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,f.id()));
        assertEquals(8000000L,sql.queryForObject("SELECT sum(amount_minor) FROM ledger_entries WHERE booking_id=? AND account_type='OWNER_PAYOUT' AND side='CREDIT'",Long.class,f.id()));
        assertEquals(400000L,sql.queryForObject("SELECT sum(amount_minor) FROM ledger_entries WHERE booking_id=? AND account_type='PLATFORM_REVENUE' AND side='CREDIT'",Long.class,f.id()));
        assertEquals(0L,sql.queryForObject("SELECT sum(CASE WHEN side='DEBIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries WHERE booking_id=?",Long.class,f.id()));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM paystack_test_transfers WHERE booking_id=?",Integer.class,f.id()));
    }
    @Test void missingDestinationUnverifiedOwnerAndProviderMismatchStayHeld() {
        Fixture f=held(RoleName.SUBLESSOR);dueTrue(f);payouts.tick();
        assertEquals("DESTINATION_REQUIRED",sql.queryForObject("SELECT test_payout_status FROM bookings WHERE id=?",String.class,f.id()));
        destination(f);sql.update("UPDATE role_verifications SET status='NOT_STARTED' WHERE user_id=?",f.owner());payouts.tick();
        assertEquals("OWNER_NOT_VERIFIED",sql.queryForObject("SELECT test_payout_status FROM bookings WHERE id=?",String.class,f.id()));
        sql.update("UPDATE role_verifications SET status='VERIFIED' WHERE user_id=?",f.owner());providerSuccess();
        when(provider.verify("mh-owner-"+f.id())).thenReturn(new Transfer("success",1,123,"fingerprint"));payouts.tick();
        assertEquals("REVIEW",sql.queryForObject("SELECT test_payout_status FROM bookings WHERE id=?",String.class,f.id()));
        assertEquals("PAID_HELD",sql.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,f.id()));
    }
    @Test void lostPostResponseNeverCreatesSecondTransferAndLaterVerificationCanFinish() {
        Fixture f=held(RoleName.LANDLORD);dueTrue(f);destination(f);providerSuccess();
        doThrow(new TransferFailure("PROVIDER_UNAVAILABLE")).when(provider).initiate(eq("mh-owner-"+f.id()),anyLong(),any());
        payouts.tick();
        assertEquals("PAID_HELD",sql.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,f.id()));
        payouts.reconcileReference("mh-owner-"+f.id());
        verify(provider,times(1)).initiate(eq("mh-owner-"+f.id()),anyLong(),any());
        assertEquals("ACTIVE",sql.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,f.id()));
    }
    @Test void pendingOtpAndFailedAreNotPaidAndNeverCreateAnotherTransfer() {
        Fixture f=held(RoleName.SUBLESSOR);dueTrue(f);destination(f);providerSuccess();
        String ref="mh-owner-"+f.id();
        for(String state:List.of("pending","otp","failed")) {
            when(provider.verify(ref)).thenReturn(new Transfer(state,8000000,123,"fingerprint"));
            if(state.equals("pending")) payouts.tick(); else payouts.reconcileReference(ref);
            assertEquals("PAID_HELD",sql.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,f.id()));
            assertEquals(0,sql.queryForObject("SELECT count(*) FROM ledger_entries WHERE booking_id=? AND account_type='OWNER_PAYOUT'",Integer.class,f.id()));
        }
        payouts.reconcileReference(ref);
        verify(provider,times(1)).initiate(eq(ref),anyLong(),any());
        assertEquals("FAILED",sql.queryForObject("SELECT test_payout_status FROM bookings WHERE id=?",String.class,f.id()));
    }
    @Test void verifiedReversalAddsCompensatingEntriesOnlyOnce() {
        Fixture f=held(RoleName.LANDLORD);dueTrue(f);destination(f);providerSuccess();payouts.tick();
        String ref="mh-owner-"+f.id();
        when(provider.verify(ref)).thenReturn(new Transfer("reversed",8000000,123,"fingerprint"));
        payouts.reconcileReference(ref);payouts.reconcileReference(ref);
        assertEquals("REVERSED",sql.queryForObject("SELECT test_payout_status FROM bookings WHERE id=?",String.class,f.id()));
        assertEquals(0L,sql.queryForObject("SELECT sum(CASE WHEN side='CREDIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries WHERE booking_id=? AND account_type='OWNER_PAYOUT'",Long.class,f.id()));
        assertEquals(4,sql.queryForObject("SELECT count(*) FROM ledger_entries WHERE booking_id=? AND account_type='OWNER_PAYOUT'",Integer.class,f.id()));
    }
    @Test void initialDestinationBindingReplaysButCannotChangeDestination() {
        Fixture f=held(RoleName.LANDLORD);
        UUID key=UUID.randomUUID(),staff=user("ADMIN");
        sql.update("INSERT INTO staff_accounts(user_id,staff_role) VALUES (?,'STAFF_LEAD')",staff);
        when(provider.recipient("RCP_first")).thenReturn(new Recipient(123,"RCP_first","first-fingerprint"));
        payouts.configure(staff,key,f.owner(),"RCP_first");
        payouts.configure(staff,key,f.owner(),"RCP_first");
        when(provider.recipient("RCP_second")).thenReturn(new Recipient(456,"RCP_second","second-fingerprint"));
        assertThrows(RuntimeException.class,()->payouts.configure(staff,UUID.randomUUID(),f.owner(),"RCP_second"));
        assertEquals(123L,sql.queryForObject("SELECT recipient_id FROM paystack_test_destinations WHERE owner_id=?",Long.class,f.owner()));
    }
    @Test void blockedSetupRowsCannotStarveAReadyPayout() {
        for(int i=0;i<25;i++) {
            Fixture blocked=held(RoleName.LANDLORD);dueTrue(blocked);
            sql.update("UPDATE bookings SET test_payout_status='DESTINATION_REQUIRED',updated_at='2000-01-01' WHERE id=?",blocked.id());
        }
        Fixture ready=held(RoleName.SUBLESSOR);dueTrue(ready);destination(ready);providerSuccess();
        payouts.tick();
        assertEquals("ACTIVE",sql.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,ready.id()));
        verify(provider,times(1)).initiate(eq("mh-owner-"+ready.id()),anyLong(),any());
    }
    @AfterAll void close() {if(factory!=null)factory.destroy();if(sql!=null)sql.execute("DROP SCHEMA "+schema+" CASCADE");}
}
