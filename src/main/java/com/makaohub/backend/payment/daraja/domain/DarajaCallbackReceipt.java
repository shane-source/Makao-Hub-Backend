package com.makaohub.backend.payment.daraja.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Immutable, minimal record of one Daraja STK callback. */
@Entity
@Table(name = "daraja_callback_receipts")
public class DarajaCallbackReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "checkout_request_id", nullable = false, unique = true, length = 120)
    private String checkoutRequestId;

    @Column(name = "merchant_request_id", nullable = false, length = 120)
    private String merchantRequestId;

    @Column(name = "result_code", nullable = false)
    private int resultCode;

    @Column(name = "result_description", nullable = false, length = 1024)
    private String resultDescription;

    @Column(name = "mpesa_receipt_number", unique = true, length = 64)
    private String mpesaReceiptNumber;

    @Column(name = "amount_minor")
    private Long amountMinor;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected DarajaCallbackReceipt() {
    }

    public DarajaCallbackReceipt(
            String checkoutRequestId,
            String merchantRequestId,
            int resultCode,
            String resultDescription,
            String mpesaReceiptNumber,
            Long amountMinor,
            Instant receivedAt
    ) {
        this.checkoutRequestId = checkoutRequestId;
        this.merchantRequestId = merchantRequestId;
        this.resultCode = resultCode;
        this.resultDescription = resultDescription;
        this.mpesaReceiptNumber = mpesaReceiptNumber;
        this.amountMinor = amountMinor;
        this.receivedAt = receivedAt;
    }

    public UUID getId() { return id; }
    public String getCheckoutRequestId() { return checkoutRequestId; }
    public String getMerchantRequestId() { return merchantRequestId; }
    public int getResultCode() { return resultCode; }
    public String getResultDescription() { return resultDescription; }
    public String getMpesaReceiptNumber() { return mpesaReceiptNumber; }
    public Long getAmountMinor() { return amountMinor; }
    public Instant getReceivedAt() { return receivedAt; }
}
