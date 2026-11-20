package com.makaohub.backend.notification.service;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Emails every new in-app notification to its recipient: the same title and message they see in the app. */
@Service
@ConditionalOnProperty(name = "makao.notifications.email.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationEmailDelivery {
    private final JdbcTemplate db;
    private final JavaMailSender mail;
    private final String from;

    public NotificationEmailDelivery(JdbcTemplate db, JavaMailSender mail, EmailOtpProperties properties) {
        this.db = db;
        this.mail = mail;
        this.from = properties.fromEmail();
    }

    @Scheduled(fixedDelayString = "${makao.notifications.email.poll-ms:10000}")
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
        var rows = db.query("""
                SELECT u.email, n.title, n.body FROM notifications n JOIN users u ON u.id=n.user_id
                WHERE n.id=? AND u.account_status='ACTIVE'
                """, (row, index) -> new String[] {row.getString(1), row.getString(2), row.getString(3)}, notificationId);
        if (rows.isEmpty() || !EmailOtpProperties.validEmail(rows.getFirst()[0])) {
            finish(notificationId, "SKIPPED");
            return;
        }
        String title = clean(rows.getFirst()[1]);
        String body = rows.getFirst()[2] == null ? "" : rows.getFirst()[2].trim();
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(rows.getFirst()[0]);
        message.setSubject(title.isEmpty() ? "You have a Makao Hub notification" : title);
        message.setText(body + "\n\nSign in to Makao Hub to see more.");
        try {
            mail.send(message);
            finish(notificationId, "SENT");
        } catch (org.springframework.mail.MailException failure) {
            // SMTP failures can be ambiguous; do not retry automatically and send duplicates.
            finish(notificationId, "FAILED");
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private void finish(UUID id, String state) {
        db.update("UPDATE notification_email_outbox SET state=?, updated_at=CURRENT_TIMESTAMP WHERE notification_id=? AND state='SENDING'", state, id);
    }
}
