package com.makaohub.backend.notification.service;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Delivers new listing review notifications without releasing an older notification backlog. */
@Service
@ConditionalOnProperty(name="makao.notifications.listing-email.enabled", havingValue="true", matchIfMissing=true)
public class ListingReviewEmailDelivery {
    private final JdbcTemplate db;
    private final JavaMailSender mail;
    private final String from;
    public ListingReviewEmailDelivery(JdbcTemplate db, JavaMailSender mail, EmailOtpProperties properties) {
        this.db=db; this.mail=mail; this.from=properties.fromEmail();
    }
    @Scheduled(fixedDelayString="${makao.notifications.email.poll-ms:30000}")
    public void deliverPending() {
        for (int count=0; count<20; count++) {
            var ids=db.query("""
                UPDATE notification_email_outbox SET state='SENDING',updated_at=CURRENT_TIMESTAMP
                WHERE notification_id=(
                    SELECT o.notification_id FROM notification_email_outbox o
                    JOIN notifications n ON n.id=o.notification_id
                    WHERE o.state='PENDING' AND n.type='LISTING_REVIEWED'
                      AND o.created_at >= (SELECT enabled_at FROM listing_email_activation WHERE id=TRUE)
                    ORDER BY o.created_at,o.notification_id LIMIT 1 FOR UPDATE OF o SKIP LOCKED
                ) RETURNING notification_id
                """,(row,index)->row.getObject(1,UUID.class));
            if (ids.isEmpty()) return;
            deliver(ids.getFirst());
        }
    }
    void deliver(UUID id) {
        var emails=db.query("SELECT u.email FROM notifications n JOIN users u ON u.id=n.user_id WHERE n.id=? AND u.account_status='ACTIVE'",
                (row,index)->row.getString(1),id);
        String state="SKIPPED";
        if (!emails.isEmpty() && EmailOtpProperties.validEmail(emails.getFirst())) {
            SimpleMailMessage message=new SimpleMailMessage();
            message.setFrom(from); message.setTo(emails.getFirst()); message.setSubject("Makao Hub listing review update");
            message.setText("Your listing review is complete. Sign in to Makao Hub to see the result.");
            try { mail.send(message); state="SENT"; }
            catch (MailException failure) { state="FAILED"; }
        }
        db.update("UPDATE notification_email_outbox SET state=?,updated_at=CURRENT_TIMESTAMP WHERE notification_id=? AND state='SENDING'",state,id);
    }
}
