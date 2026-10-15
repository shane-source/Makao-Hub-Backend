package com.makaohub.backend.messaging.api;

import java.util.List;

public record RoommateChatMessagePageResponse(
        List<RoommateChatMessageResponse> items,
        String nextCursor,
        boolean hasMore
) {
}
