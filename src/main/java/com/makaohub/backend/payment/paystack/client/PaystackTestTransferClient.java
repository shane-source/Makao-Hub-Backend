package com.makaohub.backend.payment.paystack.client;

import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.*;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** TEST-only API calls; no provider bodies, credentials or recipient details are logged. */
@Service @Profile("paystack")
public class PaystackTestTransferClient {
    private final PaystackProperties properties;
    private final RestClient http;
    @Autowired public PaystackTestTransferClient(PaystackProperties properties) { this(properties,client()); }
    PaystackTestTransferClient(PaystackProperties properties,RestClient http) { this.properties=properties; this.http=http; }
    private static RestClient client() {
        var factory=new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(10_000); factory.setReadTimeout(20_000);
        return RestClient.builder().baseUrl("https://api.paystack.co").requestFactory(factory).build();
    }
    public void requireTestMode() {
        if (!properties.enabled() || properties.secretKey()==null || !properties.secretKey().startsWith("sk_test_")
                || !"https://api.paystack.co".equals(properties.baseUrl())) throw new TransferFailure("TEST_MODE_REQUIRED");
    }
    public Recipient recipient(String codeOrId) {
        if(codeOrId==null || !codeOrId.matches("(?:RCP_[a-zA-Z0-9]+|[0-9]+)")) throw new TransferFailure("INVALID_RECIPIENT");
        return recipientData(get("/transferrecipient/{id}",codeOrId));
    }
    /** Creates only an individual KES TEST M-Pesa recipient; the phone is never returned or stored locally. */
    public Recipient createMpesaRecipient(String name,String phone) {
        requireTestMode();
        if(name==null || name.isBlank() || phone==null || !phone.matches("0[17][0-9]{8}"))
            throw new TransferFailure("INVALID_RECIPIENT_DETAILS");
        try {
            JsonNode data=validEnvelope(http.post().uri("/transferrecipient")
                .headers(h->h.setBearerAuth(properties.secretKey())).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type","mobile_money","name",name,"account_number",phone,
                    "bank_code","MPESA","currency","KES")).retrieve().body(JsonNode.class));
            if(!"mobile_money".equals(text(data,"type"))
                    || !"MPESA".equals(text(data.path("details"),"bank_code"))
                    || !phone.equals(text(data.path("details"),"account_number")))
                throw new TransferFailure("RECIPIENT_MISMATCH");
            return recipientData(data);
        } catch(RestClientException failure) { throw new TransferFailure("PROVIDER_UNAVAILABLE"); }
    }
    private static Recipient recipientData(JsonNode data) {
        String code=text(data,"recipient_code"); long id=data.path("id").asLong(0);
        if(!"test".equals(text(data,"domain")) || !"KES".equals(text(data,"currency"))
                || !data.path("active").asBoolean(false) || id<=0 || !code.matches("RCP_[a-zA-Z0-9]+")
                || !List.of("mobile_money","kepss").contains(text(data,"type"))) throw new TransferFailure("TEST_KES_RECIPIENT_REQUIRED");
        return new Recipient(id,code,fingerprint(code));
    }
    public void verifyCollection(String reference,long total) {
        JsonNode data=get("/transaction/verify/{reference}",reference);
        if(!"test".equals(text(data,"domain")) || !"success".equals(text(data,"status"))
                || !reference.equals(text(data,"reference")) || data.path("amount").asLong(-1)!=total
                || !"KES".equals(text(data,"currency"))) throw new TransferFailure("COLLECTION_NOT_VERIFIED");
    }
    public void initiate(String reference,long amount,Recipient recipient) {
        if(amount<=0 || !reference.matches("mh-owner-[a-f0-9-]{36}")) throw new TransferFailure("INVALID_INSTRUCTION");
        requireTestMode();
        try {
            validEnvelope(http.post().uri("/transfer").headers(h->h.setBearerAuth(properties.secretKey()))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("source","balance","amount",amount,"currency","KES",
                    "reference",reference,"recipient",recipient.code(),"reason","Makao Hub TEST rent and deposit"))
                .retrieve().body(JsonNode.class));
        } catch(RestClientException failure) { throw new TransferFailure("PROVIDER_UNAVAILABLE"); }
        // Never release based on this response; a separate verify request is mandatory.
    }
    public Transfer verify(String reference) {
        if(reference==null || !reference.matches("mh-owner-[a-f0-9-]{36}")) throw new TransferFailure("INVALID_REFERENCE");
        JsonNode data=get("/transfer/verify/{reference}",reference);
        if(!"test".equals(text(data,"domain")) || !"KES".equals(text(data,"currency"))
                || !reference.equals(text(data,"reference"))) throw new TransferFailure("TRANSFER_MISMATCH");
        Recipient recipient=recipientData(data.path("recipient"));
        return new Transfer(text(data,"status"),data.path("amount").asLong(-1),recipient.id(),recipient.fingerprint());
    }
    private JsonNode get(String path,String parameter) {
        requireTestMode();
        try { return validEnvelope(http.get().uri(path,parameter).headers(h->h.setBearerAuth(properties.secretKey()))
                .retrieve().body(JsonNode.class)); }
        catch(RestClientResponseException failure) { throw new TransferFailure(failure.getStatusCode().value()==404 ? "NOT_FOUND":"PROVIDER_UNAVAILABLE"); }
        catch(RestClientException failure) { throw new TransferFailure("PROVIDER_UNAVAILABLE"); }
    }
    private static JsonNode validEnvelope(JsonNode envelope) {
        if(envelope==null || !envelope.path("status").asBoolean(false) || !envelope.hasNonNull("data"))
            throw new TransferFailure("INVALID_PROVIDER_RESPONSE");
        return envelope.path("data");
    }
    private static String text(JsonNode data,String field) { return data.path(field).asText(""); }
    public static String fingerprint(String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public record Recipient(long id,String code,String fingerprint) { @Override public String toString(){ return "Recipient[REDACTED]"; } }
    public record Transfer(String status,long amountMinor,long recipientId,String recipientFingerprint) { }
    public static class TransferFailure extends RuntimeException { public TransferFailure(String code) { super(code); } }
}
