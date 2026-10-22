package com.flowops.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;

/** Only application roles from the verified token grant access; OAuth scopes are not operator permissions. */
public final class FlowOpsRoles {
    private FlowOpsRoles() {}

    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Object claim = jwt.getClaims().get("flowops_roles");
        if (!(claim instanceof List<?> roles)) return List.of();
        return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(role -> role.equals("flowops-viewer") || role.equals("flowops-operator"))
                .distinct().<GrantedAuthority>map(role -> new SimpleGrantedAuthority(
                        role.equals("flowops-operator") ? "ROLE_OPERATOR" : "ROLE_VIEWER"))
                .toList();
    }

    public static List<String> names(Jwt jwt) {
        return authorities(jwt).stream().map(authority -> authority.getAuthority().equals("ROLE_OPERATOR")
                ? "operator" : "viewer").sorted().toList();
    }
}
