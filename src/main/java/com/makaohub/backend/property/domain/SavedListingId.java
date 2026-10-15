package com.makaohub.backend.property.domain;

import java.io.Serializable;
import java.util.UUID;

public class SavedListingId implements Serializable {

    private UUID userId;

    private UUID listingId;

    public SavedListingId() {
    }

    public SavedListingId(
            UUID userId,
            UUID listingId
    ) {
        this.userId = userId;
        this.listingId = listingId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getListingId() {
        return listingId;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof SavedListingId other)) {
            return false;
        }

        return userId.equals(other.userId)
                && listingId.equals(other.listingId);
    }

    @Override
    public int hashCode() {
        return 31 * userId.hashCode()
                + listingId.hashCode();
    }
}