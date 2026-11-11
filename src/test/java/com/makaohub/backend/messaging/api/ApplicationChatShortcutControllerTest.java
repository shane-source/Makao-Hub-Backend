package com.makaohub.backend.messaging.api;

import com.makaohub.backend.messaging.service.RentalChatThreadService;
import com.makaohub.backend.messaging.service.RoommateChatThreadService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationChatShortcutControllerTest {
    private final JdbcTemplate sql = mock(JdbcTemplate.class);
    private final RentalChatThreadService rental = mock(RentalChatThreadService.class);
    private final RoommateChatThreadService roommate = mock(RoommateChatThreadService.class);
    private final ApplicationChatShortcutController controller = new ApplicationChatShortcutController(sql, rental, roommate);
    private final Jwt jwt = mock(Jwt.class);
    private final UUID owner = UUID.randomUUID(), application = UUID.randomUUID(), thread = UUID.randomUUID();

    @Test void landlordShortcutUsesOwnedApplicationAndExistingPermissionCheck() {
        when(jwt.getSubject()).thenReturn(owner.toString());
        when(sql.query(contains("l.landlord_id=? AND t.landlord_id=?"), any(RowMapper.class), eq(application), eq(owner), eq(owner))).thenReturn(List.of(thread));
        var expected = mock(RentalChatThreadResponse.class);
        when(rental.findForLandlord(owner, thread)).thenReturn(expected);
        assertSame(expected, controller.landlord(jwt, application));
        verify(rental).findForLandlord(owner, thread);
        verifyNoInteractions(roommate);
    }

    @Test void sublessorShortcutRequiresSameRenterAndListing() {
        when(jwt.getSubject()).thenReturn(owner.toString());
        when(sql.query(contains("i.renter_id=a.renter_id"), any(RowMapper.class), eq(application), eq(owner), eq(owner))).thenReturn(List.of(thread));
        var expected = mock(RoommateChatThreadResponse.class);
        when(roommate.findForSublessor(owner, thread)).thenReturn(expected);
        assertSame(expected, controller.sublessor(jwt, application));
        verify(roommate).findForSublessor(owner, thread);
        verifyNoInteractions(rental);
    }

    @Test void missingOrOtherOwnersConversationIsNotOpenedOrCreated() {
        when(jwt.getSubject()).thenReturn(owner.toString());
        when(sql.query(anyString(), any(RowMapper.class), eq(application), eq(owner), eq(owner))).thenReturn(List.of());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.landlord(jwt, application)).getStatusCode().value());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> controller.sublessor(jwt, application)).getStatusCode().value());
        verifyNoInteractions(rental, roommate);
    }
}
