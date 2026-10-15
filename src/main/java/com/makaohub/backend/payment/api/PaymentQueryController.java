package com.makaohub.backend.payment.api;

import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.payment.repository.PaymentIntentRepository;
import com.makaohub.backend.shared.api.PageResponse;
import com.makaohub.backend.shared.api.PaginationPolicy;
import jakarta.validation.Valid;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Owner-only read endpoints for payment status and history. */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentQueryController {

    private static final int MAX_PAGE_SIZE = 50;

    private final PaymentIntentRepository paymentIntentRepository;

    public PaymentQueryController(PaymentIntentRepository paymentIntentRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
    }

    @PostMapping("/status")
    public PaymentIntentResponse status(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PaymentStatusRequest request
    ) {
        UUID userId = authenticatedUserId(jwt);
        requirePaymentRole(jwt);

        return PaymentIntentResponse.from(
                paymentIntentRepository
                        .findByIdAndPayerUserId(request.paymentIntentId(), userId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Payment intent was not found."
                        ))
        );
    }

    @GetMapping
    public PageResponse<PaymentIntentResponse> history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = authenticatedUserId(jwt);
        requirePaymentRole(jwt);

        var pageable = PaginationPolicy.pageRequest(
                page,
                size,
                MAX_PAGE_SIZE,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );

        return PageResponse.from(
                paymentIntentRepository.findAllByPayerUserId(userId, pageable)
                        .map(PaymentIntentResponse::from)
        );
    }

    private static UUID authenticatedUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw unauthorized();
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw unauthorized();
        }
    }

    private static void requirePaymentRole(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null || roles.size() != 1) {
            throw unauthorized();
        }
        try {
            RoleName role = RoleName.valueOf(roles.getFirst());
            if (role == RoleName.ADMIN) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Use the admin payment review endpoint."
                );
            }
        } catch (IllegalArgumentException exception) {
            throw unauthorized();
        }
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "A valid access token is required."
        );
    }
}
