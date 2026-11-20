package com.makaohub.backend.renter.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;

/** What a renter tells the host when applying: the day they want to move in, and an optional short message. */
public record ApplicationSubmission(LocalDate moveInDate, String message) {

    private static final ZoneId KENYA = ZoneId.of("Africa/Nairobi");

    /** Returns a checked copy: date required, today or later (within a year), message trimmed to 500 characters. */
    public ApplicationSubmission validated() {
        LocalDate today = LocalDate.now(KENYA);
        if (moveInDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the date you want to move in.");
        }
        if (moveInDate.isBefore(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The move-in date cannot be in the past.");
        }
        if (moveInDate.isAfter(today.plusYears(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a move-in date within the next year.");
        }
        String text = message == null ? null : message.strip();
        if (text != null && text.isEmpty()) text = null;
        if (text != null && text.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The message must not exceed 500 characters.");
        }
        return new ApplicationSubmission(moveInDate, text);
    }

    @Override public String toString() { return "ApplicationSubmission[redacted]"; }
}
