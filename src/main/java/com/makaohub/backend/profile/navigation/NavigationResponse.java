package com.makaohub.backend.profile.navigation;

import com.makaohub.backend.auth.domain.RoleName;

import java.util.List;

public record NavigationResponse(
        RoleName role,
        List<NavigationItemResponse> items
) {
    public NavigationResponse {
        items = List.copyOf(items);
    }
}