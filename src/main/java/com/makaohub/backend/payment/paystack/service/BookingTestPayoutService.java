package com.makaohub.backend.payment.paystack.service;

import com.makaohub.backend.admin.*;
import com.makaohub.backend.payment.domain.*;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.payment.ledger.service.MockEscrowLedgerService;
import com.makaohub.backend.payment.paystack.client.PaystackTestTransferClient;
import com.makaohub.backend.payment.paystack.client.PaystackTestTransferClient.*;
import com.makaohub.backend.renter.domain.*;
import com.makaohub.backend.renter.repository.BookingRepository;
import com.makaohub.backend.notification.service.NotificationService;
import com.makaohub.backend.notification.domain.NotificationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;

/** Durable TEST-only outbox. Provider requests run outside database transactions. */
@Service @Profile("paystack") @EnableScheduling
public class BookingTestPayoutService {
    private final JdbcTemplate sql;
    private final BookingRepository bookings;
    private final PaymentIntentRepository payments;
    private final MockEscrowLedgerService ledger;
    private final NotificationService notices;
    private final PaystackTestTransferClient provider;
    private final TransactionTemplate tx;
    private final AdminStore audit;
    private final boolean enabled;
    public BookingTestPayoutService(JdbcTemplate sql,BookingRepository bookings,PaymentIntentRepository payments,
            MockEscrowLedgerService ledger,NotificationService notices,PaystackTestTransferClient provider,
            PlatformTransactionManager manager,AdminStore audit,
            @Value("${makao.paystack.test-transfers-enabled:true}") boolean enabled) {
        this.sql=sql; this.bookings=bookings; this.payments=payments; this.ledger=ledger; this.notices=notices;
        this.provider=provider; this.tx=new TransactionTemplate(manager); this.audit=audit; this.enabled=enabled;
    }
    public Map<String,Object> configure(UUID actor,UUID key,UUID owner,String recipientCode) {
        provider.requireTestMode();
        Recipient recipient=provider.recipient(recipientCode);
        return tx.execute(status->audit.command(actor,key,"test-destination:"+owner+":"+recipient.fingerprint(),()->{
            var row=sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",owner);
            if(row.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Owner not found.");
            String role=ownerRole(owner);
            if(role==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"An active, verified landlord or sublessor is required.");
            var existing=sql.queryForList("SELECT recipient_id,recipient_fingerprint FROM paystack_test_destinations WHERE owner_id=?",owner);
            if(!existing.isEmpty()) {
                if(((Number)existing.getFirst().get("recipient_id")).longValue()!=recipient.id()
                        || !recipient.fingerprint().equals(existing.getFirst().get("recipient_fingerprint")))
                    throw new ResponseStatusException(HttpStatus.CONFLICT,"Destination changes require re-verification and cooling-off; this initial-setup route cannot change one.");
                return Map.of("configured",true);
            }
            sql.update("INSERT INTO paystack_test_destinations(owner_id,owner_role,recipient_id,recipient_fingerprint,configured_by) VALUES (?,?,?,?,?)",
                    owner,role,recipient.id(),recipient.fingerprint(),actor);
            audit.audit(actor,"TEST_PAYOUT_DESTINATION_BOUND",owner,"Staff confirmed the owner's TEST destination; provider validated test mode and KES",null,"CONFIGURED");
            return Map.of("configured",true);
        }));
    }
    /** Owner's first TEST destination only. Later changes remain blocked pending a separate re-verification flow. */
    public Map<String,Object> ownerDestinationStatus(UUID owner) {
        return Map.of("configured",!sql.queryForList("SELECT owner_id FROM paystack_test_destinations WHERE owner_id=?",owner).isEmpty(),
                "eligible",ownerRole(owner)!=null);
    }
    public Map<String,Object> configureOwnMpesa(UUID owner,UUID key,String phone) {
        provider.requireTestMode();
        if(ownerRole(owner)==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A verified landlord or sublessor role is required.");
        var existing=sql.queryForList("SELECT setup_key FROM paystack_test_destinations WHERE owner_id=?",owner);
        if(!existing.isEmpty()) return existing.getFirst().get("setup_key")!=null && key.equals(existing.getFirst().get("setup_key"))
                ? Map.of("configured",true) : throwDestinationChange();
        String name=sql.queryForObject("SELECT full_name FROM users WHERE id=?",String.class,owner);
        Recipient recipient=provider.createMpesaRecipient(name,phone);
        return tx.execute(status->{
            var row=sql.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",owner);
            if(row.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Owner not found.");
            String role=ownerRole(owner);
            if(role==null) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"A verified landlord or sublessor role is required.");
            var current=sql.queryForList("SELECT setup_key FROM paystack_test_destinations WHERE owner_id=?",owner);
            if(!current.isEmpty()) return current.getFirst().get("setup_key")!=null && key.equals(current.getFirst().get("setup_key"))
                    ? Map.of("configured",true) : throwDestinationChange();
            sql.update("INSERT INTO paystack_test_destinations(owner_id,owner_role,recipient_id,recipient_fingerprint,configured_by,setup_key) VALUES (?,?,?,?,?,?)",
                    owner,role,recipient.id(),recipient.fingerprint(),owner,key);
            audit.audit(owner,"TEST_PAYOUT_DESTINATION_BOUND",owner,
                    "Verified owner configured own initial TEST M-Pesa destination; provider validated test KES recipient",null,"CONFIGURED");
            return Map.of("configured",true);
        });
    }
    private static Map<String,Object> throwDestinationChange() {
        throw new ResponseStatusException(HttpStatus.CONFLICT,"A TEST payout destination is already configured. Changes require re-verification and cooling-off.");
    }
    @Scheduled(fixedDelayString="${makao.paystack.test-payout-poll-ms:30000}")
    public void tick() {
        if(!enabled) return;
        try { provider.requireTestMode(); } catch(TransferFailure unavailable) { return; }
        var due=sql.queryForList("""
            SELECT b.id FROM bookings b WHERE b.status='PAID_HELD' AND b.move_in_accepted IS TRUE
              AND b.release_eligible_at<=CURRENT_TIMESTAMP
              AND NOT EXISTS(SELECT 1 FROM paystack_test_transfers t WHERE t.booking_id=b.id)
              AND b.test_payout_status<>'REVIEW_REQUIRED'
              AND (b.test_payout_status<>'DESTINATION_REQUIRED' OR EXISTS (
                SELECT 1 FROM paystack_test_destinations d WHERE d.owner_id=b.owner_id))
              AND (b.test_payout_status<>'OWNER_NOT_VERIFIED' OR EXISTS (
                SELECT 1 FROM users u JOIN role_verifications v ON v.user_id=u.id
                JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id AND r.name=v.role
                WHERE u.id=b.owner_id AND u.account_status='ACTIVE' AND v.status='VERIFIED'
                  AND v.role=CASE WHEN b.listing_kind='LANDLORD_LISTING' THEN 'LANDLORD' ELSE 'SUBLESSOR' END
                  AND (SELECT count(*) FROM user_roles WHERE user_id=u.id)=1))
            ORDER BY b.updated_at,b.id LIMIT 25
            """,UUID.class);
        for(UUID id:due) {
            try { tx.executeWithoutResult(status->prepare(id)); }
            catch(RuntimeException failure) { flagPreparation(id); }
        }
        var work=sql.queryForList("""
            SELECT reference FROM paystack_test_transfers WHERE state IN ('QUEUED','UNKNOWN','PENDING','OTP')
              AND next_check_at<=CURRENT_TIMESTAMP AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP)
            ORDER BY next_check_at LIMIT 10
            """,String.class);
        for(String reference:work) reconcileReference(reference);
    }
    private void prepare(UUID id) {
        Booking booking=bookings.findByIdForUpdate(id).orElseThrow();
        if(booking.getStatus()!=BookingStatus.PAID_HELD || !booking.isReleaseEligible(Instant.now())) return;
        String role=ownerRole(booking.getOwnerId());
        String expected=booking.getListingKind()==BookingListingKind.LANDLORD_LISTING?"LANDLORD":"SUBLESSOR";
        if(!expected.equals(role)) { booking.setTestPayoutStatus("OWNER_NOT_VERIFIED"); bookings.saveAndFlush(booking); return; }
        PaymentIntent payment=payment(booking);
        var destinations=sql.queryForList("SELECT * FROM paystack_test_destinations WHERE owner_id=? AND owner_role=?",booking.getOwnerId(),role);
        if(destinations.isEmpty()) { booking.setTestPayoutStatus("DESTINATION_REQUIRED"); bookings.saveAndFlush(booking); return; }
        var destination=destinations.getFirst();
        int inserted=sql.update("""
            INSERT INTO paystack_test_transfers(booking_id,payment_intent_id,owner_id,recipient_id,recipient_fingerprint,amount_minor,reference)
            VALUES (?,?,?,?,?,?,?) ON CONFLICT(booking_id) DO NOTHING
            """,id,payment.getId(),booking.getOwnerId(),destination.get("recipient_id"),destination.get("recipient_fingerprint"),
                Math.addExact(booking.getRentAmountMinor(),booking.getDepositAmountMinor()),"mh-owner-"+id);
        if(inserted==0) return;
        booking.setTestPayoutStatus("QUEUED"); bookings.saveAndFlush(booking);
        audit.audit(null,"TEST_TRANSFER_QUEUED",id,"Fresh renter True and five-minute wait satisfied; immutable outbox created",null,"QUEUED");
    }
    private void flagPreparation(UUID id) {
        tx.executeWithoutResult(status->{
            var booking=bookings.findByIdForUpdate(id).orElse(null);
            if(booking!=null && booking.getStatus()==BookingStatus.PAID_HELD) {
                booking.setTestPayoutStatus("REVIEW_REQUIRED"); bookings.saveAndFlush(booking);
            }
        });
    }
    /** Also invoked only after webhook signature validation; unknown references do nothing. */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void reconcileReference(String reference) {
        if(!enabled || reference==null || !reference.matches("mh-owner-[a-f0-9-]{36}")) return;
        provider.requireTestMode();
        Job job=tx.execute(status->claim(reference));
        if(job==null) return;
        try {
            if(job.first()) {
                if(!Boolean.TRUE.equals(tx.execute(status->mayInitiate(job)))) { finish(job,"REVIEW",null); return; }
                Recipient recipient=provider.recipient(Long.toString(job.recipient()));
                if(!job.fingerprint().equals(recipient.fingerprint())) { finish(job,"REVIEW",null); return; }
                var collection=tx.execute(status->payment(bookings.findById(job.booking()).orElseThrow()));
                provider.verifyCollection("MH-"+collection.getId(),collection.getAmountMinor());
                provider.initiate(job.reference(),job.amount(),recipient);
            }
            Transfer result=provider.verify(job.reference());
            if(result.amountMinor()!=job.amount() || result.recipientId()!=job.recipient()
                    || !job.fingerprint().equals(result.recipientFingerprint())) { finish(job,"REVIEW",null); return; }
            String state=switch(result.status()) {
                case "success" -> "SUCCEEDED";
                case "pending","processing","queued","received" -> "PENDING";
                case "otp" -> "OTP";
                case "failed" -> "FAILED";
                case "reversed" -> "REVERSED";
                default -> "REVIEW";
            };
            finish(job,state,result);
        } catch(TransferFailure failure) {
            // Never create another reference or blindly repeat an ambiguous POST.
            finish(job,List.of("TRANSFER_MISMATCH","COLLECTION_NOT_VERIFIED","TEST_KES_RECIPIENT_REQUIRED")
                    .contains(failure.getMessage())?"REVIEW":null,null);
        } catch(RuntimeException failure) {
            // A local invariant/finalization failure is not a transport retry: stop and expose review.
            // The immutable provider reference remains available for staff reconciliation.
            finish(job,"REVIEW",null);
        }
    }
    private Job claim(String reference) {
        var rows=sql.queryForList("SELECT * FROM paystack_test_transfers WHERE reference=? AND (lease_until IS NULL OR lease_until<CURRENT_TIMESTAMP) FOR UPDATE",reference);
        if(rows.isEmpty()) return null;
        var row=rows.getFirst(); String state=(String)row.get("state");
        if(List.of("FAILED","REVERSED","REVIEW").contains(state)) return null;
        UUID lease=UUID.randomUUID(); boolean first=state.equals("QUEUED");
        sql.update("""
            UPDATE paystack_test_transfers SET state=CASE WHEN state='QUEUED' THEN 'UNKNOWN' ELSE state END,
              attempted_at=COALESCE(attempted_at,CURRENT_TIMESTAMP),lease_token=?,lease_until=CURRENT_TIMESTAMP+INTERVAL '3 minutes',
              updated_at=CURRENT_TIMESTAMP WHERE reference=?
            """,lease,reference);
        return new Job((UUID)row.get("booking_id"),((Number)row.get("recipient_id")).longValue(),(String)row.get("recipient_fingerprint"),
                ((Number)row.get("amount_minor")).longValue(),reference,first,lease);
    }
    private boolean mayInitiate(Job job) {
        Booking booking=bookings.findByIdForUpdate(job.booking()).orElseThrow();
        return booking.getStatus()==BookingStatus.PAID_HELD && booking.isReleaseEligible(Instant.now())
                && (booking.getListingKind()==BookingListingKind.LANDLORD_LISTING?"LANDLORD":"SUBLESSOR")
                    .equals(ownerRole(booking.getOwnerId())) && payment(booking)!=null;
    }
    private void finish(Job job,String next,Transfer result) {
        tx.executeWithoutResult(status->{
            Booking booking=bookings.findByIdForUpdate(job.booking()).orElseThrow();
            var rows=sql.queryForList("SELECT state FROM paystack_test_transfers WHERE reference=? AND lease_token=? FOR UPDATE",job.reference(),job.lease());
            if(rows.isEmpty()) return;
            String before=(String)rows.getFirst().get("state");
            String after=next==null?before:next;
            if(before.equals("SUCCEEDED") && !List.of("SUCCEEDED","REVERSED").contains(after)) after="REVIEW";
            if(!before.equals("SUCCEEDED") && after.equals("REVERSED")) after="REVIEW";
            if(after.equals("SUCCEEDED") && !before.equals("SUCCEEDED")) {
                PaymentIntent payment=payment(booking);
                booking.markReleased(Instant.now(),job.reference(),job.reference());
                ledger.recordBookingRelease(payment,booking,booking.getRenterId());
                ledger.recordTestTransferSettlement(payment,booking,false);
                notices.bookingNotice(booking.getOwnerId(),booking.getId(),NotificationType.TEST_PAYOUT_STATUS,
                        "TEST payout confirmed","Paystack verified the TEST transfer of rent and deposit. No real money moved.");
                notices.bookingNotice(booking.getRenterId(),booking.getId(),NotificationType.TEST_PAYOUT_STATUS,
                        "TEST payout confirmed","Your True response and the five-minute wait were checked. Paystack verified the TEST payout.");
            } else if(after.equals("REVERSED") && before.equals("SUCCEEDED")) {
                ledger.recordTestTransferSettlement(payments.findById((UUID)sql.queryForMap(
                        "SELECT payment_intent_id FROM paystack_test_transfers WHERE booking_id=?",job.booking()).get("payment_intent_id")).orElseThrow(),booking,true);
            }
            sql.update("UPDATE paystack_test_transfers SET state=?,lease_until=NULL,lease_token=NULL,next_check_at=CURRENT_TIMESTAMP+INTERVAL '30 seconds',updated_at=CURRENT_TIMESTAMP WHERE reference=?",after,job.reference());
            booking.setTestPayoutStatus(after); bookings.saveAndFlush(booking);
            if(!before.equals(after)) audit.audit(null,"TEST_TRANSFER_STATUS",job.booking(),"System reconciliation with Paystack; no callback-only settlement",before,after);
        });
    }
    private PaymentIntent payment(Booking booking) {
        if(payments.existsByBookingIdAndPaymentTypeAndStatus(booking.getId(),PaymentType.BOOKING,PaymentStatus.DISPUTED))
            throw new IllegalStateException("Payment is disputed.");
        var payment=payments.findFirstByBookingIdAndPaymentTypeAndStatusOrderByCreatedAtDesc(booking.getId(),PaymentType.BOOKING,PaymentStatus.SUCCEEDED).orElseThrow();
        if(payment.getAmountMinor()!=booking.getTotalAmountMinor() || !"KES".equals(payment.getCurrency())
                || !("MH-"+payment.getId()).equals(payment.getProviderReference())) throw new IllegalStateException("Paystack TEST collection is required.");
        return payment;
    }
    private String ownerRole(UUID owner) {
        var roles=sql.queryForList("""
            SELECT r.name FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
            JOIN role_verifications v ON v.user_id=u.id AND v.role=r.name
            WHERE u.id=? AND u.account_status='ACTIVE' AND r.name IN ('LANDLORD','SUBLESSOR') AND v.status='VERIFIED'
              AND (SELECT count(*) FROM user_roles WHERE user_id=u.id)=1
            """,String.class,owner);
        return roles.size()==1?roles.getFirst():null;
    }
    private record Job(UUID booking,long recipient,String fingerprint,long amount,String reference,boolean first,UUID lease) { }
}
