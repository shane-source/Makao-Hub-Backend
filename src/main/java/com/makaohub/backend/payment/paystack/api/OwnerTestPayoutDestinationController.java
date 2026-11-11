package com.makaohub.backend.payment.paystack.api;

import com.makaohub.backend.payment.paystack.client.PaystackTestTransferClient.TransferFailure;
import com.makaohub.backend.payment.paystack.service.BookingTestPayoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Active owner role and JWT subject select the only destination that can be configured. */
@RestController @Profile("paystack")
@RequestMapping({"/api/v1/landlord/payments/test-destination","/api/v1/sublessor/payments/test-destination"})
public class OwnerTestPayoutDestinationController {
    private final BookingTestPayoutService payouts;
    public OwnerTestPayoutDestinationController(BookingTestPayoutService payouts) { this.payouts=payouts; }
    @GetMapping
    public Map<String,Object> status(@AuthenticationPrincipal Jwt jwt) { return payouts.ownerDestinationStatus(owner(jwt)); }
    @PostMapping
    public Map<String,Object> configure(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Request request) {
        try { return payouts.configureOwnMpesa(owner(jwt),key,request.phone()); }
        catch(TransferFailure failure) { throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Paystack TEST M-Pesa recipient setup did not complete. Check the number and TEST transfer availability."); }
    }
    private static UUID owner(Jwt jwt) {
        if(jwt==null || jwt.getSubject()==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        List<String> roles=jwt.getClaimAsStringList("roles");
        if(roles==null || roles.size()!=1 || !List.of("LANDLORD","SUBLESSOR").contains(roles.getFirst()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try { return UUID.fromString(jwt.getSubject()); }
        catch(IllegalArgumentException failure) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }
    public record Request(@NotNull @Pattern(regexp="0[17][0-9]{8}",message="Enter a Kenyan M-Pesa number in 07XXXXXXXX or 01XXXXXXXX format.") String phone,
            @AssertTrue(message="Confirm this TEST M-Pesa number belongs to you.") boolean ownNumberConfirmed) {
        @Override public String toString() { return "Request[REDACTED]"; }
    }
}
