package com.makaohub.backend.messaging.service;

import com.makaohub.backend.storage.service.SupabaseStorageClient;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatInboxMetadataServiceTest {
    @Test
    void emptyPagesDoNotReadPrivateData() {
        JdbcTemplate sql = mock(JdbcTemplate.class);
        SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
        var service = new ChatInboxMetadataService(sql, storage, new ChatContactMaskingService());
        assertTrue(service.forThreads(UUID.randomUUID(), ChatInboxMetadataService.Kind.RENTAL, List.of()).isEmpty());
        verifyNoInteractions(sql, storage);
    }

    @Test
    void previewsAreMaskedAndParticipantRestrictionsArePresent() throws Exception {
        JdbcTemplate sql = mock(JdbcTemplate.class);
        SupabaseStorageClient storage = mock(SupabaseStorageClient.class);
        ResultSet row = mock(ResultSet.class);
        UUID actor = UUID.randomUUID(), thread = UUID.randomUUID();
        when(row.getObject("id", UUID.class)).thenReturn(thread);
        when(row.getString("full_name")).thenReturn("Example Person");
        when(row.getString("masked_body")).thenReturn("email test@example.com");
        doAnswer(call -> {
            String query = call.getArgument(0);
            assertTrue(query.contains("t.renter_id=? OR t.sublessor_id=?"));
            assertTrue(query.contains("viewer.account_status='ACTIVE'"));
            assertTrue(query.contains("t.status='OPEN'"));
            assertTrue(query.contains("EXISTS (SELECT 1 FROM user_roles"));
            ((RowCallbackHandler) call.getArgument(1)).processRow(row);
            return null;
        }).when(sql).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        var service = new ChatInboxMetadataService(sql, storage, new ChatContactMaskingService());
        var result = service.forThreads(actor, ChatInboxMetadataService.Kind.ROOMMATE, List.of(thread));
        assertEquals("email [contact hidden]", result.get(thread).lastMessage());
        assertFalse(result.get(thread).toString().contains("Example Person"));
        verifyNoInteractions(storage);
    }
}
