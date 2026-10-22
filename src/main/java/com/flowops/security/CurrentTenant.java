package com.flowops.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Resolves tenant scope exclusively from the JWT already verified by the security filter. */
@Component
public class CurrentTenant {
    public UUID id() {
        // AccessTokenClaimsValidator guarantees a complete UUID before any controller executes.
        // Never fall back to a demo tenant or accept a tenant header/query/body parameter.
        return UUID.fromString(jwt().getClaimAsString("tenant_id"));
    }

    public Jwt jwt() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken bearer) || !bearer.isAuthenticated()) {
            throw new AccessDeniedException("Verified bearer identity required");
        }
        return bearer.getToken();
    }
}
