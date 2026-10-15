package com.makaohub.backend.messaging.api;

import java.util.List;

/**
 * One cursor-paginated slice of authorized rental-chat history.
 *
 * Messages are ordered newest first.
 */
public record RentalChatMessagePageResponse(
        List<RentalChatMessageResponse> items,
        String nextCursor,
        boolean hasNext
) {

    /**
     * Prevents the returned message list from being modified.
     */
    public RentalChatMessagePageResponse {
        items = List.copyOf(items);
    }
}