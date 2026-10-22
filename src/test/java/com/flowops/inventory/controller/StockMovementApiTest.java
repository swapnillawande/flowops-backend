package com.flowops.inventory.controller;

import com.flowops.inventory.repository.StockMovementRepository;
import com.flowops.inventory.repository.StockRepository;
import com.flowops.product.entity.Product;
import com.flowops.product.repository.ProductRepository;
import com.flowops.support.PostgresIntegrationTest;
import com.flowops.support.AuthenticatedApiTestConfiguration;
import org.springframework.context.annotation.Import;
import com.flowops.tenant.DemoTenant;
import com.flowops.warehouse.entity.Warehouse;
import com.flowops.warehouse.repository.WarehouseRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthenticatedApiTestConfiguration.class)
class StockMovementApiTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired StockRepository stocks;
    @Autowired StockMovementRepository movements;
    @Autowired WarehouseRepository warehouses;
    @Autowired ProductRepository products;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private UUID warehouseId;
    private UUID productId;
    private String stockUrl;
    private String endpoint;

    @BeforeEach
    void setUp() throws Exception {
        movements.deleteAllInBatch();
        stocks.deleteAllInBatch();
        products.deleteAllInBatch();
        warehouses.deleteAllInBatch();
        warehouseId = warehouses.saveAndFlush(new Warehouse(DemoTenant.ID, "Berlin", "Berlin", "DE")).getId();
        productId = products.saveAndFlush(new Product(DemoTenant.ID, "LAPTOP", "Laptop", BigDecimal.ONE)).getId();
        String collection = "/api/v1/warehouses/" + warehouseId + "/stock";
        stockUrl = collection + "/" + productId;
        endpoint = stockUrl + "/movements";
        mvc.perform(post(collection).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":\"" + productId + "\",\"initialQuantity\":25}"))
                .andExpect(status().isCreated());
    }

    @Test
    void openingReceiptAndAdjustmentFormBalanceHistory() throws Exception {
        postMovement(body(UUID.randomUUID(), "RECEIPT", "10", " Delivery received "), 201);
        postMovement(body(UUID.randomUUID(), "ADJUSTMENT", "-3", "Damaged items"), 201);
        mvc.perform(get(stockUrl)).andExpect(status().isOk())
                .andExpect(jsonPath("onHand").value(32)).andExpect(jsonPath("available").value(32))
                .andExpect(jsonPath("version").value(2));
        mvc.perform(get(endpoint)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("OPENING"))
                .andExpect(jsonPath("$[0].onHandBefore").value(0))
                .andExpect(jsonPath("$[0].onHandAfter").value(25))
                .andExpect(jsonPath("$[1].reason").value("Delivery received"))
                .andExpect(jsonPath("$[1].onHandBefore").value(25))
                .andExpect(jsonPath("$[1].onHandAfter").value(35))
                .andExpect(jsonPath("$[2].quantity").value(-3))
                .andExpect(jsonPath("$[2].onHandAfter").value(32));
    }

    @Test
    void identicalRetryReturnsOriginalMovementEvenAfterLaterChanges() throws Exception {
        UUID requestId = UUID.randomUUID();
        String original = body(requestId, "RECEIPT", "10", "Delivery");
        String response = mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        postMovement(body(UUID.randomUUID(), "ADJUSTMENT", "5", "Correction"), 201);
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(original))
                .andExpect(status().isOk()).andExpect(content().json(response));
        assertEquals(40, balance());
        assertEquals(3, movements.count());
    }

    @Test
    void changedPayloadCannotReuseRequestId() throws Exception {
        UUID requestId = UUID.randomUUID();
        postMovement(body(requestId, "RECEIPT", "10", "Delivery"), 201);
        for (String changed : List.of(body(requestId, "RECEIPT", "11", "Delivery"),
                body(requestId, "ADJUSTMENT", "10", "Delivery"),
                body(requestId, "RECEIPT", "10", "Different reason"))) {
            postMovement(changed, 409);
        }
        assertEquals(35, balance());
        assertEquals(2, movements.count());
    }

    @Test
    void negativeBalanceAndReservedStockAreProtected() throws Exception {
        postMovement(body(UUID.randomUUID(), "ADJUSTMENT", "-26", "Too many"), 409);
        jdbc.update("UPDATE stock SET reserved = 20 WHERE warehouse_id = ?", warehouseId);
        postMovement(body(UUID.randomUUID(), "ADJUSTMENT", "-6", "Would consume reserved units"), 409);
        postMovement(body(UUID.randomUUID(), "ADJUSTMENT", "-5", "Remove only free units"), 201);
        assertEquals(20, balance());
        mvc.perform(get(stockUrl)).andExpect(jsonPath("reserved").value(20))
                .andExpect(jsonPath("available").value(0));
        assertEquals(2, movements.count());
    }

    @Test
    void integerOverflowDoesNotWrapBalance() throws Exception {
        postMovement(body(UUID.randomUUID(), "RECEIPT", "2147483647", "Too many"), 409);
        assertEquals(25, balance());
        assertEquals(1, movements.count());
    }

    @Test
    void invalidInputNeverChangesBalanceOrHistory() throws Exception {
        for (String invalid : List.of("{}", body(UUID.randomUUID(), "OPENING", "1", "Forbidden type"),
                body(UUID.randomUUID(), "RECEIPT", "0", "Zero"),
                body(UUID.randomUUID(), "RECEIPT", "-1", "Negative receipt"),
                body(UUID.randomUUID(), "ADJUSTMENT", "0", "Zero"),
                body(UUID.randomUUID(), "ADJUSTMENT", "1.5", "Fractional"),
                body(UUID.randomUUID(), "RECEIPT", "1", " "),
                body(UUID.randomUUID(), "RECEIPT", "1", "X".repeat(251)))) {
            postMovement(invalid, 400);
        }
        assertEquals(25, balance());
        assertEquals(1, movements.count());
    }

    @Test
    void auditInsertFailureRollsBackBalanceUpdate() throws Exception {
        jdbc.execute("ALTER TABLE stock_movements ADD CONSTRAINT test_reject_reason CHECK (reason <> 'Reject audit')");
        try {
            postMovement(body(UUID.randomUUID(), "RECEIPT", "10", "Reject audit"), 409);
            assertEquals(25, balance());
            assertEquals(1, movements.count());
        } finally {
            jdbc.execute("ALTER TABLE stock_movements DROP CONSTRAINT test_reject_reason");
        }
    }

    @Test
    void tenantAndWarehouseScopesApplyToHistoryAndWrites() throws Exception {
        UUID otherWarehouse = warehouses.saveAndFlush(
                new Warehouse(UUID.randomUUID(), "Private", "Berlin", "DE")).getId();
        String inaccessible = "/api/v1/warehouses/" + otherWarehouse + "/stock/" + productId + "/movements";
        mvc.perform(get(inaccessible)).andExpect(status().isNotFound());
        mvc.perform(post(inaccessible).contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "RECEIPT", "1", "Unauthorized")))
                .andExpect(status().isNotFound());
        String missingStock = "/api/v1/warehouses/" + warehouseId + "/stock/" + UUID.randomUUID() + "/movements";
        mvc.perform(get(missingStock)).andExpect(status().isNotFound());
        mvc.perform(post(missingStock).contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "RECEIPT", "1", "Missing")))
                .andExpect(status().isNotFound());
        assertEquals(25, balance());
        assertEquals(1, movements.count());
    }

    @Test
    void concurrentReceiptsDoNotLoseUpdates() throws Exception {
        var responses = concurrently(body(UUID.randomUUID(), "RECEIPT", "10", "Delivery A"),
                body(UUID.randomUUID(), "RECEIPT", "7", "Delivery B"));
        assertEquals(List.of(201, 201), responses);
        assertEquals(42, balance());
        assertEquals(3, movements.count());
    }

    @Test
    void concurrentDuplicateRequestsApplyOnlyOnce() throws Exception {
        String request = body(UUID.randomUUID(), "RECEIPT", "10", "Delivery");
        assertEquals(List.of(200, 201), concurrently(request, request));
        assertEquals(35, balance());
        assertEquals(2, movements.count());
    }

    @Test
    void concurrentDeductionsCannotOverdrawStock() throws Exception {
        assertEquals(List.of(201, 409), concurrently(
                body(UUID.randomUUID(), "ADJUSTMENT", "-20", "Correction A"),
                body(UUID.randomUUID(), "ADJUSTMENT", "-20", "Correction B")));
        assertEquals(5, balance());
        assertEquals(2, movements.count());
    }

    @Test
    void migrationBackfillsExistingOpeningBalance() {
        try {
            Flyway.configure().dataSource(dataSource).schemas("upgrade_test").defaultSchema("upgrade_test")
                    .target("4").load().migrate();
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO upgrade_test.stock
                    (id, tenant_id, warehouse_id, product_id, on_hand, reserved, version, created_at)
                    VALUES (?, ?, ?, ?, 25, 5, 0, CURRENT_TIMESTAMP)
                    """, id, DemoTenant.ID, warehouseId, productId);
            Flyway.configure().dataSource(dataSource).schemas("upgrade_test").defaultSchema("upgrade_test")
                    .load().migrate();
            assertEquals(25, jdbc.queryForObject(
                    "SELECT on_hand_after FROM upgrade_test.stock_movements WHERE stock_id = ?", Integer.class, id));
            assertEquals("OPENING", jdbc.queryForObject(
                    "SELECT type FROM upgrade_test.stock_movements WHERE stock_id = ?", String.class, id));
            assertEquals(5, jdbc.queryForObject("SELECT reserved FROM upgrade_test.stock WHERE id = ?", Integer.class, id));
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS upgrade_test CASCADE");
        }
    }

    private void postMovement(String body, int expectedStatus) throws Exception {
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    private int balance() {
        return stocks.findByTenantIdAndWarehouseIdAndProductId(DemoTenant.ID, warehouseId, productId)
                .orElseThrow().getOnHand();
    }

    private List<Integer> concurrently(String firstBody, String secondBody) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> first = () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(firstBody))
                        .andReturn().getResponse().getStatus();
            };
            Callable<Integer> second = () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(secondBody))
                        .andReturn().getResponse().getStatus();
            };
            var a = executor.submit(first);
            var b = executor.submit(second);
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)).stream().sorted().toList();
        }
    }

    private String body(UUID requestId, String type, String quantity, String reason) {
        return "{\"requestId\":\"" + requestId + "\",\"type\":\"" + type + "\",\"quantity\":"
                + quantity + ",\"reason\":\"" + reason + "\"}";
    }
}
