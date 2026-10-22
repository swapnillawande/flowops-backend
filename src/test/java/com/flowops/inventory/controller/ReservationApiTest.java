package com.flowops.inventory.controller;

import com.flowops.inventory.repository.*;
import com.flowops.product.entity.Product;
import com.flowops.product.repository.ProductRepository;
import com.flowops.support.PostgresIntegrationTest;
import com.flowops.support.AuthenticatedApiTestConfiguration;
import org.springframework.context.annotation.Import;
import com.flowops.tenant.DemoTenant;
import com.flowops.warehouse.entity.Warehouse;
import com.flowops.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercises transactions against real PostgreSQL, including competing requests on independent threads. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthenticatedApiTestConfiguration.class)
class ReservationApiTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired StockRepository stocks;
    @Autowired StockMovementRepository movements;
    @Autowired ReservationRepository reservations;
    @Autowired ReservationItemRepository items;
    @Autowired ProductRepository products;
    @Autowired WarehouseRepository warehouses;
    @Autowired JdbcTemplate jdbc;

    private UUID warehouseId;
    private UUID firstProduct;
    private UUID secondProduct;
    private String endpoint;

    @BeforeEach
    void setUp() throws Exception {
        items.deleteAllInBatch();
        reservations.deleteAllInBatch();
        movements.deleteAllInBatch();
        stocks.deleteAllInBatch();
        products.deleteAllInBatch();
        warehouses.deleteAllInBatch();
        warehouseId = warehouses.saveAndFlush(new Warehouse(DemoTenant.ID, "Berlin", "Berlin", "DE")).getId();
        firstProduct = products.saveAndFlush(new Product(DemoTenant.ID, "FIRST", "First", BigDecimal.ONE)).getId();
        secondProduct = products.saveAndFlush(new Product(DemoTenant.ID, "SECOND", "Second", BigDecimal.ONE)).getId();
        endpoint = "/api/v1/warehouses/" + warehouseId + "/reservations";
        for (UUID product : List.of(firstProduct, secondProduct)) {
            mvc.perform(post("/api/v1/warehouses/" + warehouseId + "/stock")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":\"" + product + "\",\"initialQuantity\":10}"))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void multiItemHoldChangesOnlyReservedAndLogsAfterCommit(CapturedOutput output) throws Exception {
        var location = create(body(UUID.randomUUID(), line(firstProduct, 3), line(secondProduct, 4)));
        assertReserved(firstProduct, 3);
        assertReserved(secondProduct, 4);
        mvc.perform(get(location)).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("ACTIVE"))
                .andExpect(jsonPath("items.length()").value(2))
                .andExpect(jsonPath("tenantId").doesNotExist());
        mvc.perform(post(location + "/release")).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("RELEASED"))
                .andExpect(jsonPath("releasedAt").exists());
        assertTrue(output.getOut().contains("operation=RESERVATION_CREATED"));
        assertTrue(output.getOut().contains("operation=RESERVATION_RELEASED"));
    }

    @Test
    void identicalRetryWithReorderedItemsDoesNotHoldAgain() throws Exception {
        UUID request = UUID.randomUUID();
        String location = create(body(request, line(firstProduct, 10), line(secondProduct, 10)));
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                        .content(body(request, line(secondProduct, 10), line(firstProduct, 10))))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("ACTIVE"));
        assertEquals(1, reservations.count());
        assertReserved(firstProduct, 10);
        mvc.perform(post(location + "/release")).andExpect(status().isOk());
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                        .content(body(request, line(firstProduct, 10), line(secondProduct, 10))))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("RELEASED"));
        assertReserved(firstProduct, 0);
    }

    @Test
    void changedPayloadCannotReuseRequestId() throws Exception {
        UUID request = UUID.randomUUID();
        create(body(request, line(firstProduct, 3)));
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(request, line(firstProduct, 4))))
                .andExpect(status().isConflict());
        // Even disjoint product sets cannot reuse an existing idempotency key.
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body(request, line(secondProduct, 3))))
                .andExpect(status().isConflict());
        assertReserved(firstProduct, 3);
        assertReserved(secondProduct, 0);
    }

    @Test
    void failureOfAnyItemRollsBackEntireHold() throws Exception {
        mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), line(firstProduct, 3), line(secondProduct, 11))))
                .andExpect(status().isConflict());
        assertReserved(firstProduct, 0);
        assertReserved(secondProduct, 0);
        assertEquals(0, reservations.count());
        assertEquals(0, items.count());
    }

    @Test
    void releaseReturnsOnlyItsOwnUnitsAndIsRetrySafe() throws Exception {
        String first = create(body(UUID.randomUUID(), line(firstProduct, 3)));
        String second = create(body(UUID.randomUUID(), line(firstProduct, 4)));
        mvc.perform(post(first + "/release")).andExpect(status().isOk());
        mvc.perform(post(first + "/release")).andExpect(status().isOk());
        assertReserved(firstProduct, 4);
        mvc.perform(get(second)).andExpect(jsonPath("status").value("ACTIVE"));
        assertEquals(2, reservations.count());
    }

    @Test
    void reservedUnitsCannotBeRemovedByStockAdjustment() throws Exception {
        create(body(UUID.randomUUID(), line(firstProduct, 8)));
        mvc.perform(post("/api/v1/warehouses/" + warehouseId + "/stock/" + firstProduct + "/movements")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + UUID.randomUUID()
                                + "\",\"type\":\"ADJUSTMENT\",\"quantity\":-3,\"reason\":\"Damage\"}"))
                .andExpect(status().isConflict());
        assertReserved(firstProduct, 8);
    }

    @Test
    void rejectsInvalidMissingAndDuplicateItems() throws Exception {
        for (String invalid : List.of("{}", body(UUID.randomUUID()),
                body(UUID.randomUUID(), line(firstProduct, 0)),
                body(UUID.randomUUID(), line(firstProduct, -1)),
                body(UUID.randomUUID(), line(firstProduct, 1), line(firstProduct, 2)),
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"items\":[null]}",
                "{\"requestId\":\"" + UUID.randomUUID() + "\",\"items\":[{\"productId\":\"" + firstProduct + "\",\"quantity\":1.5}]}")) {
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("error").isString());
        }
        assertEquals(0, reservations.count());
        assertReserved(firstProduct, 0);
    }

    @Test
    void anotherTenantOrWarehouseCannotReadOrReleaseHold() throws Exception {
        String location = create(body(UUID.randomUUID(), line(firstProduct, 3)));
        UUID otherWarehouse = warehouses.saveAndFlush(new Warehouse(UUID.randomUUID(), "Private", "Berlin", "DE")).getId();
        UUID ownOtherWarehouse = warehouses.saveAndFlush(new Warehouse(DemoTenant.ID, "Hamburg", "Hamburg", "DE")).getId();
        for (UUID inaccessible : List.of(otherWarehouse, ownOtherWarehouse)) {
            String otherLocation = location.replace(warehouseId.toString(), inaccessible.toString());
            mvc.perform(get(otherLocation)).andExpect(status().isNotFound());
            mvc.perform(post(otherLocation + "/release")).andExpect(status().isNotFound());
        }
        mvc.perform(post(endpoint.replace(warehouseId.toString(), otherWarehouse.toString()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), line(firstProduct, 1))))
                .andExpect(status().isNotFound());
        assertReserved(firstProduct, 3);
    }

    @Test
    void missingOrAnotherTenantsStockCannotBeReserved() throws Exception {
        UUID otherProduct = products.saveAndFlush(new Product(UUID.randomUUID(), "PRIVATE", "Private", BigDecimal.ONE)).getId();
        for (UUID missing : List.of(UUID.randomUUID(), otherProduct)) {
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                            .content(body(UUID.randomUUID(), line(firstProduct, 1), line(missing, 1))))
                    .andExpect(status().isNotFound());
        }
        assertReserved(firstProduct, 0);
        assertEquals(0, reservations.count());
    }

    @Test
    void competingReservationsNeverOversell() throws Exception {
        assertEquals(List.of(201, 409), concurrently(
                createTask(body(UUID.randomUUID(), line(firstProduct, 7))),
                createTask(body(UUID.randomUUID(), line(firstProduct, 7)))));
        assertReserved(firstProduct, 7);
        assertEquals(1, reservations.count());
    }

    @Test
    void oppositeItemOrderDoesNotDeadlockAndDuplicateRequestHoldsOnce() throws Exception {
        UUID request = UUID.randomUUID();
        assertEquals(List.of(200, 201), concurrently(
                createTask(body(request, line(firstProduct, 3), line(secondProduct, 4))),
                createTask(body(request, line(secondProduct, 4), line(firstProduct, 3)))));
        assertReserved(firstProduct, 3);
        assertReserved(secondProduct, 4);
        assertEquals(1, reservations.count());
    }

    @Test
    void concurrentReleaseReturnsHeldUnitsOnlyOnce() throws Exception {
        String location = create(body(UUID.randomUUID(), line(firstProduct, 10)));
        Callable<Integer> release = () -> mvc.perform(post(location + "/release")).andReturn().getResponse().getStatus();
        assertEquals(List.of(200, 200), concurrently(release, release));
        assertReserved(firstProduct, 0);
        mvc.perform(get(location)).andExpect(jsonPath("status").value("RELEASED"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedItemInsertRollsBackStockAndDoesNotLogSuccess(CapturedOutput output) throws Exception {
        jdbc.execute("ALTER TABLE inventory_reservation_items ADD CONSTRAINT test_reject_quantity CHECK (quantity <> 3)");
        try {
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON)
                            .content(body(UUID.randomUUID(), line(firstProduct, 3))))
                    .andExpect(status().isConflict());
            assertReserved(firstProduct, 0);
            assertEquals(0, reservations.count());
            assertFalse(output.getOut().contains("operation=RESERVATION_CREATED"));
        } finally {
            jdbc.execute("ALTER TABLE inventory_reservation_items DROP CONSTRAINT test_reject_quantity");
        }
    }

    private String create(String body) throws Exception {
        return mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andReturn().getResponse().getHeader("Location");
    }

    private void assertReserved(UUID product, int expected) {
        var stock = stocks.findByTenantIdAndWarehouseIdAndProductId(DemoTenant.ID, warehouseId, product).orElseThrow();
        assertEquals(10, stock.getOnHand(), "A hold must never change physical stock");
        assertEquals(expected, stock.getReserved());
        assertEquals(10 - expected, stock.getAvailable());
    }

    private Callable<Integer> createTask(String body) {
        return () -> mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus();
    }

    private List<Integer> concurrently(Callable<Integer> a, Callable<Integer> b) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return a.call(); });
            var second = executor.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return b.call(); });
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)).stream().sorted().toList();
        }
    }

    private String body(UUID request, String... lines) {
        return "{\"requestId\":\"" + request + "\",\"items\":[" + String.join(",", lines) + "]}";
    }

    private String line(UUID product, int quantity) {
        return "{\"productId\":\"" + product + "\",\"quantity\":" + quantity + "}";
    }
}
