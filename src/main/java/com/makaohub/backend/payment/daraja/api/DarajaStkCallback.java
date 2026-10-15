package com.makaohub.backend.payment.daraja.api;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Minimal STK callback contract; raw callback bodies are never persisted. */
public record DarajaStkCallback(
        @JsonProperty("Body") Body body
) {

    public record Body(
            @JsonProperty("stkCallback") StkCallback stkCallback
    ) {
    }

    public record StkCallback(
            @JsonProperty("MerchantRequestID") String merchantRequestId,
            @JsonProperty("CheckoutRequestID") String checkoutRequestId,
            @JsonProperty("ResultCode") Integer resultCode,
            @JsonProperty("ResultDesc") String resultDescription,
            @JsonProperty("CallbackMetadata") CallbackMetadata callbackMetadata
    ) {
    }

    public record CallbackMetadata(
            @JsonProperty("Item") List<CallbackItem> items
    ) {
    }

    public record CallbackItem(
            @JsonProperty("Name") String name,
            @JsonProperty("Value") Object value
    ) {
    }
}
