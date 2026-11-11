package com.makaohub.backend.messaging.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatContactMaskingServiceTest {
    private final ChatContactMaskingService service = new ChatContactMaskingService();

    @Test void masksSpacedKenyanNumbersAndSentenceFinalEmails() {
        for (String number : new String[] {
                "0 7 1 2 3 4 5 6 7 8", "+ 2 5 4 7 1 2 3 4 5 6 7 8",
                "0712 345 678", "712345678" }) {
            var result = service.mask("Call " + number + " please");
            assertTrue(result.contactMasked(), number);
            assertEquals("Call [contact hidden] please", result.content(), number);
        }
        for (String domain : new String[] {
                "example.com", "outlook.com", "example.outlook", "example.co.ke",
                "example.org", "example.net", "example.africa" }) {
            var result = service.mask("Email me at person@" + domain + ".");
            assertTrue(result.contactMasked(), domain);
            assertEquals("Email me at [contact hidden].", result.content(), domain);
        }
    }

    @Test void chatThresholdMasksAllRemainingGroupsIncludingIncompleteSpacedNumber() {
        String content = "0 7 1 2 3 4 5 6 7";
        assertEquals(6, service.countNumericGroups(content));
        var result = service.maskChat(content, true);
        assertTrue(result.contactMasked());
        assertFalse(result.content().matches(".*\\d.*"));
        assertTrue(result.flagCodes().contains("numeric_group_limit"));
    }

    @Test void ordinaryAmountsAreNotMaskedBeforeConversationThreshold() {
        var result = service.maskChat("Rent is 40000 and deposit is 40000", false);
        assertFalse(result.contactMasked());
        assertEquals("Rent is 40000 and deposit is 40000", result.content());
    }
}
