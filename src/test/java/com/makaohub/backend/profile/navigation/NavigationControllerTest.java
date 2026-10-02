package com.makaohub.backend.profile.navigation;

import com.makaohub.backend.auth.config.SecurityConfig;
import com.makaohub.backend.auth.domain.RoleName;
import com.makaohub.backend.storage.config.UploadSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NavigationController.class)
@Import(SecurityConfig.class)
class NavigationControllerTest {

    private static final UUID USER_ID = UUID.fromString(
            "6b93d17b-0662-4718-a17d-736d85093e75"
    );

    private static final UUID OTHER_USER_ID = UUID.fromString(
            "94b50cbd-7f48-463a-a24c-f3c6214b92d8"
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NavigationService navigationService;

    @MockitoBean
    private UploadSecurityProperties uploadSecurityProperties;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void rejectsUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/me/navigation"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(navigationService);
    }

    @Test
    void usesJwtIdentityInsteadOfRequestedUserId() throws Exception {
        NavigationResponse response = new NavigationResponse(
                RoleName.RENTER,
                List.of(
                        new NavigationItemResponse(
                                NavigationItemKey.PROFILE,
                                "/profile",
                                0,
                                true,
                                false
                        )
                )
        );

        when(navigationService.getNavigation(USER_ID))
                .thenReturn(response);

        mockMvc.perform(
                        get("/api/v1/me/navigation")
                                .param("userId", OTHER_USER_ID.toString())
                                .with(jwt().jwt(token ->
                                        token.subject(USER_ID.toString())
                                ))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("RENTER"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].key").value("PROFILE"))
                .andExpect(jsonPath("$.items[0].targetRoute")
                        .value("/profile"))
                .andExpect(jsonPath("$.items[0].badgeCount").value(0))
                .andExpect(jsonPath("$.items[0].enabled").value(true))
                .andExpect(jsonPath("$.items[0].locked").value(false));

        verify(navigationService).getNavigation(USER_ID);
        verifyNoMoreInteractions(navigationService);
    }
}