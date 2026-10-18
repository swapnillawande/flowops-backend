package com.flowops.inventory.controller;

import com.flowops.inventory.repository.StockMovementRepository;
import com.flowops.inventory.entity.Stock;
import com.flowops.inventory.repository.StockRepository;
import com.flowops.product.entity.Product;
import com.flowops.product.repository.ProductRepository;
import com.flowops.support.PostgresIntegrationTest;
import com.flowops.tenant.DemoTenant;
import com.flowops.warehouse.entity.Warehouse;
import com.flowops.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class StockApiTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired StockRepository stocks;
    @Autowired StockMovementRepository movements;
    @Autowired ProductRepository products;
    @Autowired WarehouseRepository warehouses;
    @Autowired JdbcTemplate jdbc;

    private UUID warehouseId;
    private UUID productId;
    private String endpoint;

    @BeforeEach
    void setUp() {
        movements.deleteAllInBatch();
        stocks.deleteAllInBatch();
        products.deleteAllInBatch();
        warehouses.deleteAllInBatch();
        warehouseId = warehouses.saveAndFlush(new Warehouse(DemoTenant.ID, "Berlin", "Berlin", "DE")).getId();
        productId = products.saveAndFlush(new Product(DemoTenant.ID, "LAPTOP", "Laptop", BigDecimal.ONE)).getId();
        endpoint = "/api/v1/warehouses/" + warehouseId + "/stock";
    }

    @Test
    void createsStockAndRetrievesItThroughLocation() throws Exception {
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(productId, "25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("warehouseId").value(warehouseId.toString()))
                .andExpect(jsonPath("productId").value(productId.toString()))
                .andExpect(jsonPath("onHand").value(25))
                .andExpect(jsonPath("reserved").value(0))
                .andExpect(jsonPath("available").value(25))
                .andExpect(jsonPath("version").value(0))
                .andExpect(jsonPath("createdAt").exists())
                .andExpect(jsonPath("tenantId").doesNotExist())
                .andExpect(header().string("Location", endpoint + "/" + productId))
                .andExpect(result -> mvc.perform(get(result.getResponse().getHeader("Location")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("available").value(25)));
        assertEquals(DemoTenant.ID, stocks.findAll().getFirst().getTenantId());
    }

    @Test
    void allowsZeroOpeningStock() throws Exception {
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(productId, "0")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("available").value(0));
    }

    @Test
    void duplicateOpeningStockDoesNotOverwriteQuantity() throws Exception {
        stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouseId, productId, 25));
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(productId, "100")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("error").isString());
        assertEquals(25, stocks.findAll().getFirst().getOnHand());
        assertEquals(1, stocks.count());
    }

    @Test
    void listsOnlyRequestedWarehouseAndTenant() throws Exception {
        UUID secondWarehouse = warehouses.saveAndFlush(
                new Warehouse(DemoTenant.ID, "Hamburg", "Hamburg", "DE")).getId();
        stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouseId, productId, 25));
        stocks.saveAndFlush(new Stock(DemoTenant.ID, secondWarehouse, productId, 10));
        // Deliberately insert a different tenant's row with the same location IDs.
        stocks.saveAndFlush(new Stock(UUID.randomUUID(), warehouseId, productId, 999));
        mvc.perform(get(endpoint))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].available").value(25));
    }

    @Test
    void knownWarehouseWithNoStockReturnsEmptyListAndMissingItemReturns404() throws Exception {
        mvc.perform(get(endpoint)).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(endpoint + "/" + productId)).andExpect(status().isNotFound());
    }

    @Test
    void cannotCreateStockWithAnotherTenantsWarehouseOrProduct() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        UUID otherWarehouse = warehouses.saveAndFlush(
                new Warehouse(otherTenant, "Private", "Berlin", "DE")).getId();
        UUID otherProduct = products.saveAndFlush(
                new Product(otherTenant, "PRIVATE", "Private", BigDecimal.ONE)).getId();
        String otherEndpoint = "/api/v1/warehouses/" + otherWarehouse + "/stock";
        mvc.perform(post(otherEndpoint).contentType(MediaType.APPLICATION_JSON).content(body(productId, "1")))
                .andExpect(status().isNotFound());
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(otherProduct, "1")))
                .andExpect(status().isNotFound());
        mvc.perform(get(otherEndpoint)).andExpect(status().isNotFound());
        mvc.perform(get(otherEndpoint + "/" + productId)).andExpect(status().isNotFound());
        assertEquals(0, stocks.count());
    }

    @Test
    void rejectsUnknownReferences() throws Exception {
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "1")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/warehouses/" + UUID.randomUUID() + "/stock")
                        .contentType(MediaType.APPLICATION_JSON).content(body(productId, "1")))
                .andExpect(status().isNotFound());
        assertEquals(0, stocks.count());
    }

    @Test
    void rejectsInvalidQuantitiesAndIdentifiersWithoutWriting() throws Exception {
        for (String invalid : List.of("{}", "{\"productId\":\"not-a-uuid\",\"initialQuantity\":1}",
                body(productId, "null"), body(productId, "-1"), body(productId, "1.5"),
                body(productId, "2147483648"), "{broken")) {
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("error").isString());
        }
        assertEquals(0, stocks.count());
    }

    @Test
    void simultaneousOpeningRequestsCreateExactlyOneStockRow() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Integer>) () -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                                .content(body(productId, "25")))
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            var statuses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                    .stream().sorted().toList();
            assertEquals(List.of(201, 409), statuses);
        }
        assertEquals(1, stocks.count());
        assertEquals(25, stocks.findAll().getFirst().getAvailable());
    }

    @Test
    void databaseEnforcesStockInvariants() {
        Stock saved = stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouseId, productId, 25));
        assertThrows(DataIntegrityViolationException.class,
                () -> stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouseId, productId, 1)));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE stock SET on_hand = -1 WHERE id = ?", saved.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE stock SET reserved = -1 WHERE id = ?", saved.getId()));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE stock SET reserved = 26 WHERE id = ?", saved.getId()));
        jdbc.update("UPDATE stock SET reserved = 5 WHERE id = ?", saved.getId());
        assertEquals(20, stocks.findById(saved.getId()).orElseThrow().getAvailable());
    }

    private String body(UUID product, String quantity) {
        return "{\"productId\":\"" + product + "\",\"initialQuantity\":" + quantity + "}";
    }
}
