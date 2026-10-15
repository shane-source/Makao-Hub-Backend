package com.makaohub.backend.auth.config;

import com.makaohub.backend.storage.config.UploadSecurityProperties;
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
            UploadSecurityProperties uploadSecurityProperties
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
                                "/api/v1/auth/google/verify-otp",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/forgot-password/reset"
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
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")

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
