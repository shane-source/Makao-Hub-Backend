package com.makaohub.backend.profile.navigation;

public record NavigationItemResponse(
        NavigationItemKey key,
        String targetRoute,
        int badgeCount,
        boolean enabled,
        boolean locked
) {
}