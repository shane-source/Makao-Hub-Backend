package com.makaohub.backend.payment.paystack.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Settles Paystack payments without anyone tapping "check payment".
 *
 * Paystack's own "paid" notification (webhook) cannot reach a backend running on localhost, so this asks Paystack
 * directly about every payment still waiting for a result. It reuses the normal reconcile code, so the same checks
 * apply: only a verified "success" with the right amount and currency marks a payment paid and unlocks the item.
 */
@Component
@Profile("paystack")
public class PaystackPendingSettlementJob {
    private static final Logger LOG = LoggerFactory.getLogger("makao.payments");

    private final JdbcTemplate jdbc;
    private final PaystackPaymentReconciliationService reconciliation;

    public PaystackPendingSettlementJob(JdbcTemplate jdbc, PaystackPaymentReconciliationService reconciliation) {
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${makao.paystack.auto-settle-ms:5000}", initialDelay = 20_000)
    public void settlePending() {
        List<String> references;
        try {
            references = jdbc.queryForList(
                    "SELECT provider_reference FROM payment_intents "
                            + "WHERE status = 'PENDING' AND provider_reference IS NOT NULL "
                            + "AND created_at > now() - interval '2 hours' ORDER BY created_at LIMIT 20",
                    String.class);
        } catch (RuntimeException exception) {
            LOG.warn("PAYSTACK_AUTO_SETTLE_QUERY_FAILED error={}", exception.toString());
            return;
        }
        for (String reference : references) {
            try {
                reconciliation.reconcile(reference);
            } catch (RuntimeException exception) {
                LOG.warn("PAYSTACK_AUTO_SETTLE_FAILED ref={} error={}", reference, exception.toString());
            }
        }
    }
}
