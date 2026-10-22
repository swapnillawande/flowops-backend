package com.flowops.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Stateless bearer-token boundary shared by every API controller. */
@Configuration
public class SecurityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    JwtDecoder jwtDecoder(@Value("${flowops.security.issuer-uri}") String issuer,
                          @Value("${flowops.security.jwk-set-uri}") String jwkSetUri,
                          @Value("${flowops.security.audience}") String audience) {
        if (issuer.isBlank() || audience.isBlank()) {
            throw new IllegalStateException("OIDC issuer and audience must be configured");
        }
        // Defer discovery until the first bearer token. The app can boot and expose health while
        // the identity provider is unavailable; protected requests still fail closed.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = jwkSetUri.isBlank()
                    ? NimbusJwtDecoder.withIssuerLocation(issuer).build()
                    : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer), new AccessTokenClaimsValidator(audience)));
            return decoder;
        });
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            // Deliberately omit token contents, claim values and exception text from logs and responses.
            log.debug("Authentication rejected: exceptionType={}", exception.getClass().getSimpleName());
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            writeError(response, 401, "Authentication required or access token invalid");
        };
        AccessDeniedHandler forbidden = (request, response, exception) -> {
            log.debug("Authorization rejected: exceptionType={}", exception.getClass().getSimpleName());
            writeError(response, 403, "Access denied");
        };
        http
                // CSRF protection is unnecessary here: authentication uses an explicit Authorization
                // header, never a browser-supplied cookie. Keep this decision under review if cookies are added.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(authorize -> authorize
                        // An internal error dispatch must preserve the original response status.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> {})
                        .authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden));
        return http.build();
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // All messages are application constants; no user-controlled value enters this JSON fragment.
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
