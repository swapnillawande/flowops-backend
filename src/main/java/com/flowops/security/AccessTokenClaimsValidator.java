package com.flowops.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.regex.Pattern;

/** Requires the application identity claims in addition to Spring's signature/issuer/time checks. */
final class AccessTokenClaimsValidator implements OAuth2TokenValidator<Jwt> {
    // UUID.fromString accepts shortened groups; the trust boundary requires the complete UUID form.
    private static final Pattern TENANT_ID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final OAuth2Error INVALID_CLAIMS = new OAuth2Error("invalid_token",
            "Access token is missing required application claims", null);
    private final String audience;

    AccessTokenClaimsValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object tenant = token.getClaims().get("tenant_id");
        Object subject = token.getClaims().get("sub");
        boolean valid = tenant instanceof String id && TENANT_ID.matcher(id).matches()
                && subject instanceof String sub && !sub.isBlank()
                && token.getExpiresAt() != null
                && token.getAudience() != null && token.getAudience().contains(audience);
        return valid ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_CLAIMS);
    }
}
