package com.flowops.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

/** Lets the UI display the server-verified identity before loading tenant-owned resources. */
@RestController
@RequestMapping("/api/v1/session")
public class SessionController {
    private static final Logger log = LoggerFactory.getLogger(SessionController.class);
    private final CurrentTenant currentTenant;

    public SessionController(CurrentTenant currentTenant) {
        this.currentTenant = currentTenant;
    }

    @GetMapping
    public SessionResponse get() {
        var jwt = currentTenant.jwt();
        UUID tenantId = currentTenant.id();
        Object preferredUsername = jwt.getClaims().get("preferred_username");
        String username = preferredUsername instanceof String name && !name.isBlank() ? name : jwt.getSubject();
        log.debug("Verified session read: tenantId={}", tenantId);
        return new SessionResponse(jwt.getSubject(), username, tenantId, FlowOpsRoles.names(jwt));
    }

    public record SessionResponse(String subject, String username, UUID tenantId, List<String> roles) {}
}
