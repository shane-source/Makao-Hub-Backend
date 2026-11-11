package com.makaohub.backend.notification;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.exception.AuthException;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.notification.api.NotificationController;
import com.makaohub.backend.notification.domain.Notification;
import com.makaohub.backend.notification.domain.NotificationType;
import com.makaohub.backend.notification.repository.NotificationRepository;
import com.makaohub.backend.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationAccessTest {
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(users, notifications);

    @Test void listUsesOnlyTheCurrentActiveAccount() {
        UUID actor = UUID.randomUUID(); active(actor);
        when(notifications.findAllByUserIdOrderByCreatedAtDesc(actor)).thenReturn(List.of(
                new Notification(actor, NotificationType.APPLICATION_APPROVED, "Approved", "Your application was approved.")));
        assertEquals(1, service.findMine(actor).size());
        verify(notifications).findAllByUserIdOrderByCreatedAtDesc(actor);
    }

    @Test void cannotMarkAnotherAccountsNotificationRead() {
        UUID actor = UUID.randomUUID(), id = UUID.randomUUID(); active(actor);
        Notification foreign = new Notification(UUID.randomUUID(), NotificationType.VERIFICATION_DECISION, "Review complete", "Check your status.");
        when(notifications.findById(id)).thenReturn(Optional.of(foreign));
        var failure = assertThrows(ResponseStatusException.class, () -> service.markRead(actor, id));
        assertEquals(404, failure.getStatusCode().value()); assertNull(foreign.getReadAt());
    }

    @Test void ownedReadIsIdempotentAndPreservesFirstReadTime() {
        UUID actor = UUID.randomUUID(), id = UUID.randomUUID(); active(actor);
        Notification own = new Notification(actor, NotificationType.LISTING_REVIEWED, "Listing reviewed", "Open your listing.");
        when(notifications.findById(id)).thenReturn(Optional.of(own));
        service.markRead(actor, id);
        var first = own.getReadAt(); assertNotNull(first);
        service.markRead(actor, id); assertEquals(first, own.getReadAt());
    }

    @Test void suspendedAccountCannotReadNotifications() {
        UUID actor = UUID.randomUUID();
        User user = mock(User.class); when(user.getAccountStatus()).thenReturn(AccountStatus.SUSPENDED);
        when(users.findById(actor)).thenReturn(Optional.of(user));
        assertThrows(ResponseStatusException.class, () -> service.findMine(actor));
        verifyNoInteractions(notifications);
    }

    @Test void eachMarketplaceRoleUsesItsTokenSubjectForBothEndpoints() {
        NotificationService scoped = mock(NotificationService.class);
        NotificationController controller = new NotificationController(scoped);
        UUID actor = UUID.randomUUID(), id = UUID.randomUUID();
        when(scoped.findMine(actor)).thenReturn(List.of());
        for (String role : List.of("RENTER", "LANDLORD", "SUBLESSOR")) {
            assertTrue(controller.getMyNotifications(jwt(actor, List.of(role))).isEmpty());
            controller.markRead(jwt(actor, List.of(role)), id);
        }
        verify(scoped, times(3)).findMine(actor); verify(scoped, times(3)).markRead(actor, id);
    }

    @Test void missingOrUnknownActiveRoleCannotReachTheNotificationService() {
        NotificationService scoped = mock(NotificationService.class);
        NotificationController controller = new NotificationController(scoped);
        assertThrows(AuthException.class, () -> controller.getMyNotifications(null));
        for (List<String> roles : List.of(List.<String>of(), List.of("UNKNOWN"), List.of("RENTER", "LANDLORD"))) {
            assertThrows(AuthException.class, () -> controller.getMyNotifications(jwt(UUID.randomUUID(), roles)));
        }
        verifyNoInteractions(scoped);
    }

    private void active(UUID id) {
        User user = mock(User.class); when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(users.findById(id)).thenReturn(Optional.of(user));
    }

    private static Jwt jwt(UUID actor, List<String> roles) {
        return Jwt.withTokenValue("synthetic").header("alg", "RS256").subject(actor.toString()).claim("roles", roles).build();
    }
}
