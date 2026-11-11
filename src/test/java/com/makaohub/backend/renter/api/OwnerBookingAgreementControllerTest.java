package com.makaohub.backend.renter.api;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.User;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.renter.domain.Booking;
import com.makaohub.backend.renter.service.BookingAgreementService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OwnerBookingAgreementControllerTest {
    @Test
    void ownedBookingShowsBothFullNamesWithoutExposingAnIdentifierAsLabel() {
        UUID ownerId = UUID.randomUUID();
        UUID renterId = UUID.randomUUID();
        Booking booking = mock(Booking.class);
        when(booking.getOwnerId()).thenReturn(ownerId);
        when(booking.getRenterId()).thenReturn(renterId);
        User owner = mock(User.class);
        User renter = mock(User.class);
        when(owner.getId()).thenReturn(ownerId);
        when(owner.getFullName()).thenReturn("David Kamau");
        when(renter.getId()).thenReturn(renterId);
        when(renter.getFullName()).thenReturn("Wanjiku Mwangi");
        BookingAgreementService service = mock(BookingAgreementService.class);
        UserRepository users = mock(UserRepository.class);
        when(service.ownerBookings(ownerId, RoleName.LANDLORD)).thenReturn(List.of(booking));
        when(users.findAllById(any())).thenReturn(List.of(owner, renter));

        var result = new OwnerBookingAgreementController(service, users).mine(jwt(ownerId, "LANDLORD"));

        assertEquals("Wanjiku Mwangi", result.getFirst().renterName());
        assertEquals("David Kamau", result.getFirst().ownerName());
    }

    @Test
    void renterTokenCannotReadOwnerBookingNames() {
        BookingAgreementService service = mock(BookingAgreementService.class);
        UserRepository users = mock(UserRepository.class);
        var controller = new OwnerBookingAgreementController(service, users);

        assertThrows(ResponseStatusException.class, () -> controller.mine(jwt(UUID.randomUUID(), "RENTER")));
        verify(users, never()).findAllById(any());
    }

    private static Jwt jwt(UUID id, String role) {
        return Jwt.withTokenValue("test").header("alg", "none")
                .subject(id.toString()).claim("roles", List.of(role)).build();
    }
}
