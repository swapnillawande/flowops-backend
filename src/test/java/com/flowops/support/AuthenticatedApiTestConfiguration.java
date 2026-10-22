package com.flowops.support;

import com.flowops.tenant.DemoTenant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Business API tests use a request-scoped identity, including requests sent by concurrency-test threads. */
@TestConfiguration(proxyBeanMethods = false)
public class AuthenticatedApiTestConfiguration {
    @Bean
    MockMvcBuilderCustomizer authenticatedBusinessRequests() {
        // SecurityApiTest deliberately does not import this fixture: it exercises real signed bearer tokens.
        return builder -> builder.defaultRequest(get("/").with(jwt().jwt(token -> token
                .subject("test-operator").claim("iss", "https://identity.flowops.test/realms/flowops")
                .claim("tenant_id", DemoTenant.ID.toString()))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"))));
    }
}
