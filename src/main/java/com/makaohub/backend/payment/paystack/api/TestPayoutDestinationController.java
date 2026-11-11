package com.makaohub.backend.payment.paystack.api;

import com.makaohub.backend.admin.*;
import com.makaohub.backend.payment.paystack.client.PaystackTestTransferClient.TransferFailure;
import com.makaohub.backend.payment.paystack.service.BookingTestPayoutService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController @Profile("paystack")
@RequestMapping("/api/v1/admin/payments/owners")
public class TestPayoutDestinationController {
    private final StaffAccessService access;
    private final BookingTestPayoutService payouts;
    public TestPayoutDestinationController(StaffAccessService access,BookingTestPayoutService payouts) { this.access=access; this.payouts=payouts; }
    @PostMapping("/{owner}/test-recipient")
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> configure(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID owner,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody DestinationRequest request) {
        var staff=access.require(jwt,StaffRole.Permission.STAFF);
        try { return payouts.configure(staff.userId(),key,owner,request.recipientCode()); }
        catch(TransferFailure failure) { throw new ResponseStatusException(HttpStatus.CONFLICT,
                "An active KES TEST recipient and enabled Paystack test key are required. Check setup in Paystack."); }
    }
    public record DestinationRequest(@NotBlank @Pattern(regexp="RCP_[a-zA-Z0-9]+") String recipientCode,
            @AssertTrue(message="Confirm this TEST recipient belongs to the selected owner.") boolean ownerDestinationConfirmed) {
        @Override public String toString() { return "DestinationRequest[REDACTED]"; }
    }
}
