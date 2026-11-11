package com.makaohub.backend.auth.config;

import com.makaohub.backend.storage.config.UploadSecurityProperties;
import com.makaohub.backend.admin.StaffAccessService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Hashes account passwords with BCrypt using cost factor 12.
     *
     * Passwords are hashed, not reversibly encrypted. Removing this
     * encoder would prevent secure password registration and login.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    /**
     * Converts the JWT "roles" claim into Spring Security authorities.
     *
     * For example:
     * roles: ["SUBLESSOR"]
     *
     * becomes:
     * ROLE_SUBLESSOR
     *
     * This allows hasRole("SUBLESSOR") to enforce the active role
     * carried by the server-issued access token.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter rolesConverter =
                new JwtGrantedAuthoritiesConverter();

        rolesConverter.setAuthoritiesClaimName("roles");
        rolesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter authenticationConverter =
                new JwtAuthenticationConverter();

        authenticationConverter.setJwtGrantedAuthoritiesConverter(
                rolesConverter
        );

        return authenticationConverter;
    }

    /**
     * Defines the HTTP security boundary for the application.
     *
     * Security is stateless because every protected request must
     * carry a valid access token.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter authenticationConverter,
            UploadSecurityProperties uploadSecurityProperties,
            ObjectProvider<StaffAccessService> staffAccess
    ) throws Exception {
        return http
                /*
                 * The REST API does not use cookie-based sessions.
                 * CSRF protection is therefore disabled here.
                 *
                 * If cookie authentication is introduced later,
                 * this decision must be reviewed.
                 */
                .csrf(csrf -> csrf.disable())

                /*
                 * Prevents Spring Security from creating an HTTP
                 * session. Every protected request must authenticate
                 * using its Bearer access token.
                 */
                .sessionManagement(session -> session
                        .sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                /*
                 * Limits attempts against public authentication
                 * endpoints before JWT authentication runs.
                 */
                .addFilterBefore(
                        new AuthRateLimitFilter(),
                        BearerTokenAuthenticationFilter.class
                )

                /*
                 * Limits authenticated upload URL, confirmation and
                 * verification requests.
                 *
                 * It runs after Bearer authentication so limits can
                 * be associated with the authenticated user.
                 */
                .addFilterAfter(
                        new ProtectedUploadRateLimitFilter(
                                uploadSecurityProperties
                        ),
                        BearerTokenAuthenticationFilter.class
                )

                .authorizeHttpRequests(authorize -> authorize
                        // Static navigation only: no payment lookup, verification, state change or account data.
                        .requestMatchers(HttpMethod.GET, "/payments/paystack/return").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/profile/me/phone/complete")
                        .hasAnyRole("RENTER", "LANDLORD", "SUBLESSOR")
                        .requestMatchers("/api/v1/profile/me/avatar/**")
                        .hasAnyRole("RENTER", "LANDLORD", "SUBLESSOR")
                        .requestMatchers("/api/v1/profile/me/deletion-request", "/api/v1/profile/me/account-action/**")
                        .hasAnyRole("RENTER", "LANDLORD", "SUBLESSOR")
                        /*
                         * Public operational endpoints.
                         */
                        .requestMatchers(
                                "/api/v1/status",
                                "/actuator/health",
                                "/api/v1/payments/daraja/callback",
                                "/api/v1/payments/paystack/webhook"
                        ).permitAll()

                        /*
                         * A WebSocket handshake has no Bearer header: chat
                         * authenticates the JWT in its STOMP CONNECT frame.
                         * Permit only this handshake route; the inbound
                         * interceptors reject unauthenticated chat frames.
                         */
                        .requestMatchers(HttpMethod.GET, "/ws").permitAll()

                        /*
                         * Public authentication endpoints.
                         *
                         * These remain protected by validation and
                         * AuthRateLimitFilter.
                         */
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/register/verify-otp",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/google/register",
                                "/api/v1/auth/google",
                                "/api/v1/auth/login/verify-otp",
                                "/api/v1/auth/reactivate/verify-otp",
                                "/api/v1/auth/google/verify-otp",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/forgot-password/reset",
                                "/api/v1/staff/auth/login",
                                "/api/v1/staff/auth/verify-otp",
                                "/api/v1/staff/auth/refresh",
                                "/api/v1/staff/auth/accept-invitation",
                                "/api/v1/staff/auth/forgot-password",
                                "/api/v1/staff/auth/reset-password"
                        ).permitAll()

                        /*
                         * Renter endpoints require an access token
                         * whose active role is RENTER.
                         */
                        .requestMatchers("/api/v1/renter/**")
                        .hasRole("RENTER")

                        /*
                         * Landlord endpoints require an access token
                         * whose active role is LANDLORD.
                         */
                        .requestMatchers("/api/v1/landlord/**")
                        .hasRole("LANDLORD")

                        /*
                         * Sublessor endpoints require an access token
                         * whose active role is SUBLESSOR.
                         *
                         * This is the missing global boundary being
                         * added in this change.
                         */
                        .requestMatchers("/api/v1/sublessor/**")
                        .hasRole("SUBLESSOR")

                        /*
                         * Administrative payment controls are never public.
                         * The service still performs its own active-account
                         * and ownership/state checks.
                         */
                        // Only the test-profile booking release may pass this boundary.
                        // The controller rechecks payment-reviewer MFA permission and test mode.
                        .requestMatchers(HttpMethod.POST,"/api/v1/admin/payments/bookings/*/release",
                                "/api/v1/admin/payments/owners/*/test-recipient")
                        .access((authentication,context) -> {
                            var guard=staffAccess.getIfAvailable();
                            var principal=authentication.get();
                            return new AuthorizationDecision(guard!=null && principal instanceof JwtAuthenticationToken token
                                    && guard.allows(token.getToken()));
                        })
                        // All other financial mutations remain blocked.
                        .requestMatchers(HttpMethod.POST,"/api/v1/admin/payments/**").denyAll()
                        .requestMatchers("/api/v1/admin/**")
                        .access((authentication,context) -> {
                            var guard=staffAccess.getIfAvailable();
                            var principal=authentication.get();
                            return new AuthorizationDecision(guard!=null && principal instanceof JwtAuthenticationToken token
                                    && guard.allows(token.getToken()));
                        })

                        /*
                         * Every endpoint not explicitly public still
                         * requires a valid authenticated account.
                         *
                         * Individual services must continue enforcing
                         * ownership and entitlement checks.
                         */
                        .anyRequest()
                        .authenticated()
                )

                /*
                 * Validates RS256 access tokens using the configured
                 * JwtDecoder and converts their roles claim using the
                 * converter above.
                 */
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(
                                        authenticationConverter
                                )
                        )
                )

                .build();
    }
}
