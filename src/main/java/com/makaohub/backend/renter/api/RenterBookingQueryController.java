package com.makaohub.backend.renter.api;
import com.makaohub.backend.renter.repository.BookingRepository;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.auth.domain.AccountStatus;
import com.makaohub.backend.auth.repository.UserRepository;
import com.makaohub.backend.auth.repository.UserRoleRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.UUID;
@RestController
@RequestMapping("/api/v1/renter/bookings")
public class RenterBookingQueryController {
    private final BookingRepository bookings;
    private final UserRepository users;
    private final UserRoleRepository roles;
    public RenterBookingQueryController(BookingRepository bookings,UserRepository users,UserRoleRepository roles) {
        this.bookings=bookings; this.users=users; this.roles=roles;
    }
    @GetMapping("/{id}") public BookingResponse one(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        UUID actor;
        try { if(jwt==null) throw new IllegalArgumentException(); actor=UUID.fromString(jwt.getSubject()); }
        catch(IllegalArgumentException|NullPointerException failure) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Valid authentication required."); }
        if(users.findById(actor).filter(u->u.getAccountStatus()==AccountStatus.ACTIVE).isEmpty()
            || !roles.existsByUser_IdAndRole_Name(actor,RoleName.RENTER))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Renter access required.");
        return BookingResponse.from(bookings.findByIdAndRenterId(id,actor)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Booking unavailable.")));
    }
}
