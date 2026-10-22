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
import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
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
    @Autowired DataSource dataSource;
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

    @Test
    void viewerCanReadAllHistoryButCannotRunAnyMutation() throws Exception {
        String operator = token(TENANT_A);
        var flow = flow(operator);
        String viewer = token(TENANT_A, claims -> claims.claim("flowops_roles", List.of("flowops-viewer")));
        request(get("/api/v1/session"), viewer).andExpect(jsonPath("roles[0]").value("viewer"));
        for (String path : List.of("/api/v1/health", "/api/v1/warehouses", "/api/v1/products",
                flow.stockPath(), flow.stockPath() + "/movements", "/api/v1/orders", flow.orderPath(),
                "/api/v1/shipments", flow.shipmentPath(), flow.shipmentPath() + "/history")) {
            request(get(path), viewer).andExpect(status().isOk());
        }
        // Authorization must precede validation and resource lookup, even for forged/manual requests.
        for (String path : List.of("/api/v1/warehouses", "/api/v1/products", "/api/v1/orders",
                "/api/v1/shipments", "/api/v1/warehouses/" + flow.warehouse() + "/stock",
                flow.stockPath() + "/movements", flow.orderPath() + "/cancel",
                flow.shipmentPath() + "/dispatch", flow.shipmentPath() + "/status",
                "/api/v1/warehouses/" + flow.warehouse() + "/reservations",
                "/api/v1/warehouses/" + flow.warehouse() + "/reservations/" + flow.reservation() + "/release")) {
            request(postJson(path, "{}"), viewer).andExpect(status().isForbidden())
                    .andExpect(jsonPath("error").value("Access denied"));
        }
        for (var command : List.of(put(flow.stockPath()), patch(flow.orderPath()), delete(flow.shipmentPath()))) {
            request(command, viewer).andExpect(status().isForbidden());
        }
        request(get(flow.stockPath()), operator).andExpect(jsonPath("onHand").value(10))
                .andExpect(jsonPath("reserved").value(2));
        request(get(flow.shipmentPath()), operator).andExpect(jsonPath("status").value("PREPARED"));
    }

    @Test
    void missingMalformedAndUnrelatedRolesNeverGrantBusinessAccess() throws Exception {
        for (Object roles : List.of("flowops-operator", 123, List.of("admin", "operator", "ROLE_OPERATOR"), List.of())) {
            String bearer = token(TENANT_A, claims -> claims.claim("flowops_roles", roles)
                    .claim("scope", "flowops-operator"));
            request(get("/api/v1/session"), bearer).andExpect(status().isOk())
                    .andExpect(jsonPath("roles").isEmpty());
            request(get("/api/v1/warehouses"), bearer).andExpect(status().isForbidden());
            request(postJson("/api/v1/warehouses", "{}"), bearer).andExpect(status().isForbidden());
        }
        request(get("/api/v1/warehouses"), token(TENANT_A, claims -> claims.claim("flowops_roles", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditActorsComeFromIdentityAndReplaysKeepTheOriginalCreator() throws Exception {
        String first = token(TENANT_A), second = token(TENANT_A, claims -> claims.subject("second-operator").claim("preferred_username", "second.operator"));
        var flow = flow(first);
        String movement = """
                {"requestId":"%s","type":"RECEIPT","quantity":3,"reason":"Supplier delivery",
                 "actorSubject":"forged-user","actorIssuer":"https://attacker.invalid"}
                """.formatted(UUID.randomUUID());
        JsonNode original = created(postJson(flow.stockPath() + "/movements", movement), first);
        assertEquals("operator-a", original.path("actorSubject").asText());
        assertEquals(ISSUER, original.path("actorIssuer").asText());
        assertEquals("berlin.operator", original.path("actorName").asText());
        request(postJson(flow.stockPath() + "/movements", movement), second).andExpect(status().isOk())
                .andExpect(jsonPath("id").value(original.path("id").asText()))
                .andExpect(jsonPath("actorSubject").value("operator-a"))
                .andExpect(jsonPath("actorName").value("berlin.operator"));
        request(post(flow.shipmentPath() + "/dispatch"), second).andExpect(status().isOk());
        request(get(flow.stockPath() + "/movements"), first)
                .andExpect(jsonPath("$[?(@.type == 'OPENING')].actorSubject").value(org.hamcrest.Matchers.contains("operator-a")))
                .andExpect(jsonPath("$[?(@.type == 'DISPATCH')].actorSubject").value(org.hamcrest.Matchers.contains("second-operator")));
        String transit = """
                {"requestId":"%s","status":"IN_TRANSIT","note":"On route","actorSubject":"forged"}
                """.formatted(UUID.randomUUID());
        request(postJson(flow.shipmentPath() + "/status", transit), second).andExpect(status().isOk());
        request(postJson(flow.shipmentPath() + "/status", transit), first).andExpect(status().isOk())
                .andExpect(jsonPath("actorSubject").value("second-operator"));
        request(postJson(flow.shipmentPath() + "/status", """
                {"requestId":"%s","status":"DELIVERED","note":"Received"}
                """.formatted(UUID.randomUUID())), first).andExpect(status().isOk());
        request(get(flow.shipmentPath() + "/history"), first)
                .andExpect(jsonPath("$[0].actorSubject").value("operator-a"))
                .andExpect(jsonPath("$[1].actorSubject").value("second-operator"))
                .andExpect(jsonPath("$[2].actorSubject").value("second-operator"))
                .andExpect(jsonPath("$[3].actorSubject").value("operator-a"))
                .andExpect(jsonPath("$[3].actorIssuer").value(ISSUER));
        // The synchronous cancellation listener retains the cancelling operator's identity.
        var cancellable = flow(first);
        request(post(cancellable.orderPath() + "/cancel"), second).andExpect(status().isOk());
        request(get(cancellable.shipmentPath() + "/history"), first)
                .andExpect(jsonPath("$[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$[1].actorSubject").value("second-operator"));
    }

    @Test
    void v9UpgradeDoesNotInventActorsOrChangeHistoricalBalances() {
        try {
            Flyway.configure().dataSource(dataSource).schemas("actor_upgrade").defaultSchema("actor_upgrade")
                    .target("9").load().migrate();
            UUID stock = UUID.randomUUID(), shipment = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO actor_upgrade.stock
                    (id,tenant_id,warehouse_id,product_id,on_hand,reserved,version,created_at)
                    VALUES (?,?,?,?,7,0,0,CURRENT_TIMESTAMP)
                    """, stock, TENANT_A, UUID.randomUUID(), UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO actor_upgrade.stock_movements
                    (id,tenant_id,stock_id,type,quantity,on_hand_before,on_hand_after,reason,created_at)
                    VALUES (?,?,?,'OPENING',7,0,7,'Old opening',CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), TENANT_A, stock);
            jdbc.update("""
                    INSERT INTO actor_upgrade.shipments
                    (id,tenant_id,order_id,warehouse_id,tracking_number,carrier,status,created_at)
                    VALUES (?,?,?,?,?,'Local','PREPARED',CURRENT_TIMESTAMP)
                    """, shipment, TENANT_A, UUID.randomUUID(), UUID.randomUUID(), "FLO-" + shipment);
            jdbc.update("""
                    INSERT INTO actor_upgrade.shipment_status_events
                    (id,tenant_id,shipment_id,sequence,status,note,created_at)
                    VALUES (?,?,?,1,'PREPARED','Old preparation',CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), TENANT_A, shipment);
            Flyway.configure().dataSource(dataSource).schemas("actor_upgrade").defaultSchema("actor_upgrade")
                    .load().migrate();
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM actor_upgrade.stock_movements "
                    + "WHERE actor_subject IS NULL AND actor_issuer IS NULL AND on_hand_after=7", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM actor_upgrade.shipment_status_events "
                    + "WHERE actor_subject IS NULL AND actor_issuer IS NULL AND status='PREPARED'", Integer.class));
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS actor_upgrade CASCADE");
        }
    }

    private record Flow(String warehouse, String reservation, String stockPath, String orderPath, String shipmentPath) {}
    private Flow flow(String bearer) throws Exception {
        String warehouse = created(postJson("/api/v1/warehouses", """
                {"name":"Warehouse %s","city":"Berlin","countryCode":"DE"}
                """.formatted(UUID.randomUUID())), bearer).path("id").asText();
        String product = created(postJson("/api/v1/products", """
                {"sku":"SKU-%s","name":"Laptop","weightKg":1.5}
                """.formatted(UUID.randomUUID())), bearer).path("id").asText();
        created(postJson("/api/v1/warehouses/" + warehouse + "/stock", """
                {"productId":"%s","initialQuantity":10}
                """.formatted(product)), bearer);
        var order = created(postJson("/api/v1/orders", """
                {"requestId":"%s","warehouseId":"%s","customerReference":"QA","items":[{"productId":"%s","quantity":2}]}
                """.formatted(UUID.randomUUID(), warehouse, product)), bearer);
        String shipment = created(postJson("/api/v1/shipments", """
                {"orderId":"%s","carrier":"Local"}
                """.formatted(order.path("id").asText())), bearer).path("id").asText();
        return new Flow(warehouse, order.path("reservationId").asText(), "/api/v1/warehouses/" + warehouse + "/stock/" + product,
                "/api/v1/orders/" + order.path("id").asText(), "/api/v1/shipments/" + shipment);
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
                .claim("tenant_id", tenant.toString()).claim("preferred_username", "berlin.operator")
                .claim("flowops_roles", List.of("flowops-operator"));
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
