package com.makaohub.backend.payment.paystack.client;

import com.makaohub.backend.payment.paystack.config.PaystackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PaystackTestTransferClientTest {
    private final RestClient.Builder builder=RestClient.builder().baseUrl("https://api.paystack.co");
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private final PaystackTestTransferClient client=new PaystackTestTransferClient(
            new PaystackProperties(true,"https://api.paystack.co","sk_test_synthetic"),builder.build());
    private final String reference="mh-owner-00000000-0000-0000-0000-000000000001";
    private String recipient() { return "\"domain\":\"test\",\"currency\":\"KES\",\"active\":true,\"id\":123,\"type\":\"mobile_money\",\"recipient_code\":\"RCP_synthetic\""; }
    @Test void testKeyAndOfficialHostAreMandatoryBeforeAnyProviderCall() {
        for(var properties:new PaystackProperties[]{new PaystackProperties(true,"https://api.paystack.co","sk_live_synthetic"),
                new PaystackProperties(false,"https://api.paystack.co","sk_test_synthetic"),
                new PaystackProperties(true,"https://other.invalid","sk_test_synthetic")}) {
            assertThrows(PaystackTestTransferClient.TransferFailure.class,()->new PaystackTestTransferClient(properties,builder.build()).recipient("RCP_synthetic"));
        }
        server.verify();
    }
    @Test void sendsOnlyFrozenRentAndDepositAndIndependentlyVerifiesRecipientAndTransfer() {
        server.expect(requestTo("https://api.paystack.co/transferrecipient/RCP_synthetic"))
                .andExpect(header("Authorization","Bearer sk_test_synthetic"))
                .andRespond(withSuccess("{\"status\":true,\"data\":{"+recipient()+"}}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.paystack.co/transfer")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"source\":\"balance\",\"amount\":8000000,\"currency\":\"KES\",\"reference\":\""+reference+"\",\"recipient\":\"RCP_synthetic\",\"reason\":\"Makao Hub TEST rent and deposit\"}"))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"status\":\"success\"}}",MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.paystack.co/transfer/verify/"+reference))
                .andRespond(withSuccess("{\"status\":true,\"data\":{\"domain\":\"test\",\"currency\":\"KES\",\"reference\":\""+reference+"\",\"amount\":8000000,\"status\":\"success\",\"recipient\":{"+recipient()+"}}}",MediaType.APPLICATION_JSON));
        var recipient=client.recipient("RCP_synthetic"); client.initiate(reference,8000000,recipient);
        var transfer=client.verify(reference); assertEquals(8000000,transfer.amountMinor()); assertEquals(123,transfer.recipientId());
        assertFalse(recipient.toString().contains("RCP_")); server.verify();
    }
    @Test void rejectsLiveOrWrongCurrencyRecipientAndDoesNotExposeProviderBodies() {
        server.expect(requestTo("https://api.paystack.co/transferrecipient/RCP_synthetic"))
            .andRespond(withSuccess("{\"status\":true,\"data\":{"+recipient().replace("test","live")+"}}",MediaType.APPLICATION_JSON));
        assertThrows(PaystackTestTransferClient.TransferFailure.class,()->client.recipient("RCP_synthetic")); server.verify();
    }
    @Test void collectionMustBeTestSuccessAndMatchTheEntireRenterCheckoutTotal() {
        server.expect(requestTo("https://api.paystack.co/transaction/verify/MH-synthetic"))
            .andRespond(withSuccess("{\"status\":true,\"data\":{\"domain\":\"test\",\"status\":\"success\",\"currency\":\"KES\",\"amount\":8000000,\"reference\":\"MH-synthetic\"}}",MediaType.APPLICATION_JSON));
        assertThrows(PaystackTestTransferClient.TransferFailure.class,()->client.verifyCollection("MH-synthetic",8400000)); server.verify();
    }
    @Test void createsOnlyOwnKesTestMpesaRecipientWithoutReturningThePhone() {
        server.expect(requestTo("https://api.paystack.co/transferrecipient")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization","Bearer sk_test_synthetic"))
            .andExpect(content().json("{\"type\":\"mobile_money\",\"name\":\"Synthetic Owner\",\"account_number\":\"0712345678\",\"bank_code\":\"MPESA\",\"currency\":\"KES\"}"))
            .andRespond(withSuccess("{\"status\":true,\"data\":{"+recipient()+",\"details\":{\"bank_code\":\"MPESA\",\"account_number\":\"0712345678\"}}}",MediaType.APPLICATION_JSON));
        var created=client.createMpesaRecipient("Synthetic Owner","0712345678");
        assertEquals(123,created.id()); assertFalse(created.toString().contains("0712345678")); server.verify();
    }
    @Test void rejectsProviderSubstitutionOfPayoutNumber() {
        server.expect(requestTo("https://api.paystack.co/transferrecipient"))
            .andRespond(withSuccess("{\"status\":true,\"data\":{"+recipient()+",\"details\":{\"bank_code\":\"MPESA\",\"account_number\":\"0799999999\"}}}",MediaType.APPLICATION_JSON));
        assertThrows(PaystackTestTransferClient.TransferFailure.class,
            ()->client.createMpesaRecipient("Synthetic Owner","0712345678")); server.verify();
    }
    @Test void rejectsProviderSubstitutionOfRecipientType() {
        server.expect(requestTo("https://api.paystack.co/transferrecipient"))
            .andRespond(withSuccess("{\"status\":true,\"data\":{"+recipient().replace("mobile_money","kepss")+
                ",\"details\":{\"bank_code\":\"MPESA\",\"account_number\":\"0712345678\"}}}",MediaType.APPLICATION_JSON));
        assertThrows(PaystackTestTransferClient.TransferFailure.class,
            ()->client.createMpesaRecipient("Synthetic Owner","0712345678")); server.verify();
    }
}
