package com.makaohub.backend.notification.service;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationEmailDeliveryTest {
    @Test void sendsGenericMessageAndRecordsDelivery() {
        var db = mock(JdbcTemplate.class);
        var mail = mock(JavaMailSender.class);
        var properties = mock(EmailOtpProperties.class);
        when(properties.fromEmail()).thenReturn("noreply@example.test");
        when(db.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of("user@example.test"));
        var id = UUID.randomUUID();
        new NotificationEmailDelivery(db, mail, properties).deliver(id);
        var message = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(message.capture());
        assertEquals("You have a new notification. Sign in to Makao Hub to view it.", message.getValue().getText());
        verify(db).update(anyString(), eq("SENT"), eq(id));
    }

    @Test void failedDeliveryIsRecordedWithoutRetrying() {
        var db = mock(JdbcTemplate.class);
        var mail = mock(JavaMailSender.class);
        var properties = mock(EmailOtpProperties.class);
        when(db.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of("user@example.test"));
        doThrow(new MailSendException("synthetic failure")).when(mail).send(any(SimpleMailMessage.class));
        var id = UUID.randomUUID();
        new NotificationEmailDelivery(db, mail, properties).deliver(id);
        verify(mail, times(1)).send(any(SimpleMailMessage.class));
        verify(db).update(anyString(), eq("FAILED"), eq(id));
    }

    @Test void unavailableRecipientIsSkipped() {
        var db = mock(JdbcTemplate.class);
        var mail = mock(JavaMailSender.class);
        var properties = mock(EmailOtpProperties.class);
        when(db.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        var id = UUID.randomUUID();
        new NotificationEmailDelivery(db, mail, properties).deliver(id);
        verifyNoInteractions(mail);
        verify(db).update(anyString(), eq("SKIPPED"), eq(id));
    }
}
