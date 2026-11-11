package com.makaohub.backend.notification.service;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Sends a generic email for new in-app notifications, without disclosing private content. */
@Service
@ConditionalOnProperty(name = "makao.notifications.email.enabled", havingValue = "true")
public class NotificationEmailDelivery {
    private final JdbcTemplate db;
    private final JavaMailSender mail;
    private final String from;

    public NotificationEmailDelivery(JdbcTemplate db, JavaMailSender mail, EmailOtpProperties properties) {
        this.db = db;
        this.mail = mail;
        this.from = properties.fromEmail();
    }

    @Scheduled(fixedDelayString = "${makao.notifications.email.poll-ms:30000}")
    public void deliverPending() {
        for (int count = 0; count < 20; count++) {
            var claimed = db.query("""
                    UPDATE notification_email_outbox SET state='SENDING', updated_at=CURRENT_TIMESTAMP
                    WHERE notification_id = (
                        SELECT notification_id FROM notification_email_outbox
                        WHERE state='PENDING' ORDER BY created_at, notification_id
                        FOR UPDATE SKIP LOCKED LIMIT 1
                    ) RETURNING notification_id
                    """, (row, index) -> row.getObject(1, UUID.class));
            if (claimed.isEmpty()) return;
            deliver(claimed.getFirst());
        }
    }

    void deliver(UUID notificationId) {
        var recipients = db.query("""
                SELECT u.email FROM notifications n JOIN users u ON u.id=n.user_id
                WHERE n.id=? AND u.account_status='ACTIVE'
                """, (row, index) -> row.getString(1), notificationId);
        if (recipients.isEmpty() || !EmailOtpProperties.validEmail(recipients.getFirst())) {
            finish(notificationId, "SKIPPED");
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipients.getFirst());
        message.setSubject("You have a Makao Hub notification");
        message.setText("You have a new notification. Sign in to Makao Hub to view it.");
        try {
            mail.send(message);
            finish(notificationId, "SENT");
        } catch (org.springframework.mail.MailException failure) {
            // SMTP failures can be ambiguous; do not retry automatically and send duplicates.
            finish(notificationId, "FAILED");
        }
    }

    private void finish(UUID id, String state) {
        db.update("UPDATE notification_email_outbox SET state=?, updated_at=CURRENT_TIMESTAMP WHERE notification_id=? AND state='SENDING'", state, id);
    }
}
