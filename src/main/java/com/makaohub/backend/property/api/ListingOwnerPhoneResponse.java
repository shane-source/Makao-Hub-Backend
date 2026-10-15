package com.makaohub.backend.property.api;

/** Owner contact returned only after the listing-specific phone unlock. */
public record ListingOwnerPhoneResponse(String ownerName, String phoneNumber) {
}
