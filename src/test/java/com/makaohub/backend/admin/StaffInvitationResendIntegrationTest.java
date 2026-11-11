package com.makaohub.backend.admin;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import com.makaohub.backend.auth.exception.OtpProviderException;
import com.makaohub.backend.auth.service.EmailOtpClient;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.makaohub.backend.admin.StaffInvitationService.BootstrapResult.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="MAKAO_ROLE_TEST_DB_URL",matches=".+")
class StaffInvitationResendIntegrationTest {
    @Test void resendIsAtomicSerializedAndCannotReuseOldCodesOrAcceptedInvitations() throws Exception {
        String url=System.getenv("MAKAO_ROLE_TEST_DB_URL");
        assertTrue(url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/makao_role_test"));
        String schema="staff_resend_test_"+UUID.randomUUID().toString().replace("-","");
        var source=new DriverManagerDataSource(url+"?currentSchema="+schema,
                System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_USER","hp"),
                System.getenv().getOrDefault("MAKAO_ROLE_TEST_DB_PASSWORD",""));
        Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load().migrate();
        var sql=new JdbcTemplate(source);
        var db=new AdminStore(sql);
        var mail=mock(EmailOtpClient.class);
        byte[] hmacKey=new byte[32];
        var properties=new EmailOtpProperties("sender@example.invalid",Base64.getEncoder().encodeToString(hmacKey));
        var invitations=new StaffInvitationService(db,new StaffAccessService(db),mail,new BCryptPasswordEncoder(4),properties);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        String recipient="lead@example.invalid",oldCode="A".repeat(43);
        var mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey,"HmacSHA256"));
        String emailDigest=HexFormat.of().formatHex(mac.doFinal(("staff-invitation-email:v1\n"+recipient).getBytes(StandardCharsets.UTF_8)));
        UUID id=UUID.randomUUID();

        assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->tx.execute(status->invitations.resendBootstrap(recipient))).getStatusCode().value());
        sql.update("""
                INSERT INTO staff_invitations(id,email_digest,token_digest,staff_role,can_view_documents,issued_at,expires_at)
                VALUES (?,?,?,'STAFF_LEAD',FALSE,CURRENT_TIMESTAMP-INTERVAL '25 hours',CURRENT_TIMESTAMP-INTERVAL '1 hour')
                """,id,emailDigest,AdminStore.digest(oldCode));
        var originalExpiry=sql.queryForObject("SELECT expires_at FROM staff_invitations WHERE id=?",java.sql.Timestamp.class,id);
        assertEquals(409,assertThrows(ResponseStatusException.class,
                ()->tx.execute(status->invitations.resendBootstrap("wrong@example.invalid"))).getStatusCode().value());
        verifyNoInteractions(mail);

        doThrow(new OtpProviderException(OtpProviderException.Failure.UNAVAILABLE)).when(mail).sendStaffInvitation(anyString(),anyString());
        assertThrows(OtpProviderException.class,()->tx.execute(status->invitations.resendBootstrap(recipient)));
        assertEquals(AdminStore.digest(oldCode),sql.queryForObject("SELECT token_digest FROM staff_invitations WHERE id=?",String.class,id));
        assertEquals(originalExpiry,sql.queryForObject("SELECT expires_at FROM staff_invitations WHERE id=?",java.sql.Timestamp.class,id));
        assertEquals(0,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='STAFF_INVITATION_RESENT'",Integer.class));

        var sentCode=new AtomicReference<String>();
        doAnswer(call->{sentCode.set(call.getArgument(1));return null;}).when(mail).sendStaffInvitation(eq(recipient),anyString());
        clearInvocations(mail);
        var ready=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        Callable<String> resend=()->{
            ready.countDown();
            if(!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic concurrency timeout");
            try { return tx.execute(status->invitations.resendBootstrap(recipient)).name(); }
            catch(ResponseStatusException failure) { return Integer.toString(failure.getStatusCode().value()); }
        };
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(resend);
            var second=executor.submit(resend);
            assertTrue(ready.await(10,TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Set.of("INVITATION_RESENT","429"),Set.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS)));
        }
        verify(mail,times(1)).sendStaffInvitation(eq(recipient),anyString());
        assertNotEquals(oldCode,sentCode.get());
        assertEquals(AdminStore.digest(sentCode.get()),sql.queryForObject("SELECT token_digest FROM staff_invitations WHERE id=?",String.class,id));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM staff_invitations",Integer.class));
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM admin_audit_events WHERE action='STAFF_INVITATION_RESENT'",Integer.class));
        assertTrue(Boolean.TRUE.equals(sql.queryForObject("SELECT expires_at>CURRENT_TIMESTAMP FROM staff_invitations WHERE id=?",Boolean.class,id)));
        assertThrows(ResponseStatusException.class,()->tx.execute(status->invitations.accept(
                new AdminRequests.Accept(recipient,oldCode,"Synthetic Lead","TestPassword!"))));
        tx.execute(status->invitations.accept(new AdminRequests.Accept(recipient,sentCode.get(),"Synthetic Lead","TestPassword!")));
        assertEquals(STAFF_EXISTS,tx.execute(status->invitations.resendBootstrap(recipient)));
        assertThrows(ResponseStatusException.class,()->tx.execute(status->invitations.accept(
                new AdminRequests.Accept(recipient,sentCode.get(),"Synthetic Lead","TestPassword!"))));
        verify(mail,times(1)).sendStaffInvitation(eq(recipient),anyString());
        assertEquals(1,sql.queryForObject("SELECT count(*) FROM staff_accounts",Integer.class));
    }
}
