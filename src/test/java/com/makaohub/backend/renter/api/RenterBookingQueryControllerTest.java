package com.makaohub.backend.renter.api;

import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RenterBookingQueryControllerTest {
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserRoleRepository roles = mock(UserRoleRepository.class);
    private final RenterBookingQueryController controller = new RenterBookingQueryController(bookings, users, roles);

    @Test void listUsesOnlyAuthenticatedRenterId() {
        UUID renterId = UUID.randomUUID();
        User user = mock(User.class);
        when(user.getAccountStatus()).thenReturn(AccountStatus.ACTIVE);
        when(users.findById(renterId)).thenReturn(Optional.of(user));
        when(roles.existsByUser_IdAndRole_Name(renterId, RoleName.RENTER)).thenReturn(true);
        Booking booking = mock(Booking.class);
        when(bookings.findTop50ByRenterIdOrderByCreatedAtDesc(renterId)).thenReturn(List.of(booking));

        assertEquals(1, controller.mine(jwt(renterId, "RENTER")).size());
        verify(bookings).findTop50ByRenterIdOrderByCreatedAtDesc(renterId);
    }

    @Test void otherActiveRoleCannotListRenterBookings() {
        assertThrows(ResponseStatusException.class, () -> controller.mine(jwt(UUID.randomUUID(), "LANDLORD")));
        verifyNoInteractions(bookings);
    }

    private static Jwt jwt(UUID userId, String role) {
        return Jwt.withTokenValue("test").header("alg", "none").subject(userId.toString())
                .claim("roles", List.of(role)).build();
    }
}
