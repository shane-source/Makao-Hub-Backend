package com.makaohub.backend.notification.service;

import com.makaohub.backend.auth.config.EmailOtpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ListingReviewEmailDeliveryTest {
    @Test void sendsReviewNoticeWithoutPrivateListingDetails() {
        var db=mock(JdbcTemplate.class); var mail=mock(JavaMailSender.class); var properties=mock(EmailOtpProperties.class);
        when(properties.fromEmail()).thenReturn("noreply@example.test");
        when(db.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of("owner@example.test"));
        UUID id=UUID.randomUUID(); new ListingReviewEmailDelivery(db,mail,properties).deliver(id);
        var message=org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class); verify(mail).send(message.capture());
        assertEquals("Your listing review is complete. Sign in to Makao Hub to see the result.",message.getValue().getText());
        verify(db).update(anyString(),eq("SENT"),eq(id));
    }
}
