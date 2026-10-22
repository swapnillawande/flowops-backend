package com.flowops.audit;

import jakarta.persistence.PrePersist;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Capture the identity on insertion, inside the same transaction as the business change. */
public class ActorAttributionListener {
    @PrePersist
    public void beforeInsert(ActorAttributed record) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken bearer && bearer.isAuthenticated()) {
            var jwt = bearer.getToken();
            Object preferredName = jwt.getClaims().get("preferred_username");
            // A display-name snapshot is useful to people but never determines authorization.
            String name = preferredName instanceof String text && !text.isBlank() ? text : null;
            record.captureActor(jwt.getClaimAsString("iss"), jwt.getSubject(), name);
        }
        // Migrations and direct background/service work may have no user. Preserve null rather than
        // inventing an operator. Any future scheduled workflow must define its own system attribution.
        // A replay loads an existing row and never runs PrePersist, preserving its original actor.
    }
}
