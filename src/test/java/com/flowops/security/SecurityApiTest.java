package com.flowops.security;

import com.flowops.inventory.repository.StockRepository;
import com.flowops.product.repository.ProductRepository;
import com.flowops.support.PostgresIntegrationTest;
import com.flowops.warehouse.repository.WarehouseRepository;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises the real JWT decoder/filter against an isolated RSA key server and two tenant identities. */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityApiTest extends PostgresIntegrationTest {
    private static final String ISSUER = "https://identity.flowops.test/realms/flowops";
    private static final UUID TENANT_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final RSAKey SIGNING_KEY;
    private static final HttpServer KEYS;

    static {
        try {
            SIGNING_KEY = new RSAKeyGenerator(2048).keyID("integration-test-key").generate();
            byte[] publicKeys = new JWKSet(SIGNING_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            KEYS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            KEYS.createContext("/jwks", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, publicKeys.length);
                try (var output = exchange.getResponseBody()) { output.write(publicKeys); }
            });
            KEYS.start();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void identityProperties(DynamicPropertyRegistry properties) {
        properties.add("flowops.security.issuer-uri", () -> ISSUER);
        properties.add("flowops.security.jwk-set-uri", () -> "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks");
        properties.add("flowops.security.audience", () -> "flowops-api");
    }

    @AfterAll
    static void stopKeyServer() { KEYS.stop(0); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired WarehouseRepository warehouses;
    @Autowired ProductRepository products;
    @Autowired StockRepository stocks;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE shipment_status_events, shipments, customer_order_items, customer_orders, "
                + "inventory_reservation_items, inventory_reservations, stock_movements, stock, products, warehouses CASCADE");
    }

    @Test
    void onlyHealthIsPublicAndAnonymousRequestsNeverCreateRecords() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("UP"));
        for (String path : List.of("/api/v1/session", "/api/v1/health", "/api/v1/warehouses", "/api/v1/products",
                "/api/v1/orders", "/api/v1/shipments")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
                    .andExpect(jsonPath("error").value("Authentication required or access token invalid"));
        }
        mvc.perform(post("/api/v1/warehouses").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Unauthorized\",\"city\":\"Berlin\",\"countryCode\":\"DE\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0, warehouses.count());
    }

    @Test
    void sessionUsesVerifiedClaimsAndDoesNotCreateHttpSession() throws Exception {
        request(get("/api/v1/session").header("X-Tenant-ID", TENANT_B), token(TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("subject").value("operator-a"))
                .andExpect(jsonPath("username").value("berlin.operator"))
                .andExpect(jsonPath("tenantId").value(TENANT_A.toString()))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        request(get("/api/v1/session"), token(TENANT_A, claims -> claims.claim("preferred_username", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("username").value("operator-a"));
    }

    @Test
    void rejectsInvalidSignatureIssuerAudienceAndTimeClaims() throws Exception {
        RSAKey forgedKey = new RSAKeyGenerator(2048).keyID(SIGNING_KEY.getKeyID()).generate();
        String invalidSignature = signed(claims(TENANT_A), forgedKey);
        List<String> invalid = List.of(invalidSignature, new PlainJWT(claims(TENANT_A).build()).serialize(),
                token(TENANT_A, claims -> claims.issuer(null)),
                token(TENANT_A, claims -> claims.issuer("https://attacker.invalid")),
                token(TENANT_A, claims -> claims.audience("another-api")),
                token(TENANT_A, claims -> claims.audience(List.of())),
                token(TENANT_A, claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(120)))),
                token(TENANT_A, claims -> claims.notBeforeTime(Date.from(Instant.now().plusSeconds(300)))),
                token(TENANT_A, claims -> claims.expirationTime(null)));
        for (String token : invalid) {
            request(get("/api/v1/session"), token).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("error").value("Authentication required or access token invalid"));
        }
    }

    @Test
    void requiresCanonicalTenantUuidAndNonblankSubject() throws Exception {
        // These tokens have valid signatures; malformed authorization claims must still fail closed.
        for (Object tenant : List.of("", " ", "not-a-uuid", "1-1-1-1-1", 123, List.of(TENANT_A.toString()))) {
            request(get("/api/v1/session"), token(TENANT_A, claims -> claims.claim("tenant_id", tenant)))
                    .andExpect(status().isUnauthorized());
        }
        for (Consumer<JWTClaimsSet.Builder> invalid : List.<Consumer<JWTClaimsSet.Builder>>of(
                claims -> claims.claim("tenant_id", null), claims -> claims.subject(null), claims -> claims.subject(" "))) {
            request(get("/api/v1/session"), token(TENANT_A, invalid)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void unknownRoutesDenyAuthenticatedCallersWithConsistentJson() throws Exception {
        request(get("/actuator/env"), token(TENANT_A)).andExpect(status().isForbidden())
                .andExpect(jsonPath("error").value("Access denied"));
        request(get("/internal"), token(TENANT_A)).andExpect(status().isForbidden())
                .andExpect(jsonPath("error").value("Access denied"));
    }

    @Test
    void verifiedTenantOwnsNewRecordsEvenWhenRequestClaimsAnotherTenant() throws Exception {
        String bearer = token(TENANT_A);
        JsonNode warehouse = created(post("/api/v1/warehouses").header("X-Tenant-ID", TENANT_B)
                .param("tenantId", TENANT_B.toString()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name":"Shared name","city":"Berlin","countryCode":"DE","tenantId":"%s"}
                        """.formatted(TENANT_B)), bearer);
        assertEquals(TENANT_A, warehouses.findById(UUID.fromString(warehouse.path("id").asText())).orElseThrow().getTenantId());
        request(get("/api/v1/warehouses"), token(TENANT_B)).andExpect(status().isOk())
                .andExpect(content().json("[]"));
        // Uniqueness is scoped to the authenticated tenant, so another tenant can use the same name.
        created(postJson("/api/v1/warehouses", """
                {"name":"Shared name","city":"Munich","countryCode":"DE"}
                """), token(TENANT_B));
        request(get("/api/v1/warehouses"), bearer).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].city").value("Berlin"));
    }

    @Test
    void otherTenantCannotReadOrMutateOrderShipmentReservationOrStock() throws Exception {
        String a = token(TENANT_A), b = token(TENANT_B);
        String warehouse = created(postJson("/api/v1/warehouses", """
                {"name":"Berlin","city":"Berlin","countryCode":"DE"}
                """), a).path("id").asText();
        String product = created(postJson("/api/v1/products", """
                {"sku":"LAPTOP","name":"Laptop","weightKg":1.5}
                """), a).path("id").asText();
        String stockPath = "/api/v1/warehouses/" + warehouse + "/stock/" + product;
        created(postJson("/api/v1/warehouses/" + warehouse + "/stock", """
                {"productId":"%s","initialQuantity":10}
                """.formatted(product)), a);
        JsonNode order = created(postJson("/api/v1/orders", """
                {"requestId":"%s","warehouseId":"%s","customerReference":"PRIVATE-REFERENCE", "items":[{"productId":"%s","quantity":2}]}
                """.formatted(UUID.randomUUID(), warehouse, product)), a);
        String orderPath = "/api/v1/orders/" + order.path("id").asText();
        String holdPath = "/api/v1/warehouses/" + warehouse + "/reservations/" + order.path("reservationId").asText();
        String shipment = created(postJson("/api/v1/shipments", """
                {"orderId":"%s","carrier":"Local carrier"}
                """.formatted(order.path("id").asText())), a).path("id").asText();
        String shipmentPath = "/api/v1/shipments/" + shipment;

        for (String path : List.of("/api/v1/warehouses/" + warehouse, "/api/v1/products/" + product,
                "/api/v1/warehouses/" + warehouse + "/stock", stockPath, stockPath + "/movements",
                orderPath, holdPath, shipmentPath, shipmentPath + "/history")) {
            request(get(path), b).andExpect(status().isNotFound());
        }
        for (String path : List.of("/api/v1/warehouses", "/api/v1/products")) {
            request(get(path), b).andExpect(status().isOk()).andExpect(content().json("[]"));
        }
        for (String path : List.of("/api/v1/orders", "/api/v1/shipments")) {
            request(get(path), b).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(0));
        }
        for (String path : List.of(orderPath + "/cancel", holdPath + "/release", shipmentPath + "/dispatch")) {
            request(post(path), b).andExpect(status().isNotFound());
        }
        request(postJson(stockPath + "/movements", """
                {"requestId":"%s","type":"RECEIPT","quantity":5,"reason":"Unauthorized receipt"}
                """.formatted(UUID.randomUUID())), b).andExpect(status().isNotFound());
        request(postJson(shipmentPath + "/status", """
                {"requestId":"%s","status":"IN_TRANSIT","note":"Unauthorized update"}
                """.formatted(UUID.randomUUID())), b).andExpect(status().isNotFound());
        request(postJson("/api/v1/shipments", """
                {"orderId":"%s","carrier":"Local carrier"}
                """.formatted(order.path("id").asText())), b).andExpect(status().isNotFound());
        request(postJson("/api/v1/orders", """
                {"requestId":"%s","warehouseId":"%s","customerReference":"Foreign warehouse","items":[{"productId":"%s","quantity":1}]}
                """.formatted(UUID.randomUUID(), warehouse, product)), b).andExpect(status().isNotFound());

        request(get(orderPath), a).andExpect(jsonPath("status").value("CONFIRMED"));
        request(get(shipmentPath), a).andExpect(jsonPath("status").value("PREPARED"));
        var stock = stocks.findByTenantIdAndWarehouseIdAndProductId(TENANT_A, UUID.fromString(warehouse), UUID.fromString(product)).orElseThrow();
        assertEquals(10, stock.getOnHand());
        assertEquals(2, stock.getReserved());
    }

    @Test
    void cannotAttachAnotherTenantsProductToAnOwnedWarehouse() throws Exception {
        String a = token(TENANT_A), b = token(TENANT_B);
        String warehouse = created(postJson("/api/v1/warehouses", """
                {"name":"Berlin","city":"Berlin","countryCode":"DE"}
                """), a).path("id").asText();
        String foreignProduct = created(postJson("/api/v1/products", """
                {"sku":"PRIVATE","name":"Private product","weightKg":1}
                """), b).path("id").asText();
        request(postJson("/api/v1/warehouses/" + warehouse + "/stock", """
                {"productId":"%s","initialQuantity":10}
                """.formatted(foreignProduct)), a).andExpect(status().isNotFound());
        request(postJson("/api/v1/warehouses/" + warehouse + "/stock", """
                {"productId":"%s","initialQuantity":10}
                """.formatted(foreignProduct)), b).andExpect(status().isNotFound());
        assertEquals(0, stocks.count());
        assertEquals(TENANT_B, products.findById(UUID.fromString(foreignProduct)).orElseThrow().getTenantId());
    }

    private MockHttpServletRequestBuilder postJson(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions request(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private JsonNode created(MockHttpServletRequestBuilder request, String token) throws Exception {
        return json.readTree(request(request, token).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private static JWTClaimsSet.Builder claims(UUID tenant) {
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience("flowops-api")
                .subject(tenant.equals(TENANT_A) ? "operator-a" : "operator-b")
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("tenant_id", tenant.toString()).claim("preferred_username", "berlin.operator");
    }

    private static String token(UUID tenant) throws Exception { return token(tenant, claims -> {}); }

    private static String token(UUID tenant, Consumer<JWTClaimsSet.Builder> customize) throws Exception {
        JWTClaimsSet.Builder claims = claims(tenant);
        customize.accept(claims);
        return signed(claims, SIGNING_KEY);
    }

    private static String signed(JWTClaimsSet.Builder claims, RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
