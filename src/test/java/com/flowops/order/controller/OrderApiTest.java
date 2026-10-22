package com.flowops.order.controller;

import com.flowops.inventory.repository.*;
import com.flowops.order.dto.CreateOrderRequest;
import com.flowops.order.repository.OrderItemRepository;
import com.flowops.order.repository.OrderRepository;
import com.flowops.order.service.OrderService;
import com.flowops.product.entity.Product;
import com.flowops.product.repository.ProductRepository;
import com.flowops.support.PostgresIntegrationTest;
import com.flowops.support.AuthenticatedApiTestConfiguration;
import org.springframework.context.annotation.Import;
import com.flowops.tenant.DemoTenant;
import com.flowops.warehouse.entity.Warehouse;
import com.flowops.warehouse.repository.WarehouseRepository;
import com.flowops.inventory.entity.Stock;
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

/** End-to-end transaction tests, including rollback across the order and inventory module boundary. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthenticatedApiTestConfiguration.class)
class OrderApiTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository orderItems;
    @Autowired ReservationRepository reservations;
    @Autowired ReservationItemRepository reservationItems;
    @Autowired StockRepository stocks;
    @Autowired StockMovementRepository movements;
    @Autowired ProductRepository products;
    @Autowired WarehouseRepository warehouses;
    @Autowired OrderService service;
    @Autowired JdbcTemplate jdbc;

    private UUID warehouse;
    private UUID first;
    private UUID second;

    @BeforeEach
    void setUp() {
        orderItems.deleteAllInBatch();
        orders.deleteAllInBatch();
        reservationItems.deleteAllInBatch();
        reservations.deleteAllInBatch();
        movements.deleteAllInBatch();
        stocks.deleteAllInBatch();
        products.deleteAllInBatch();
        warehouses.deleteAllInBatch();
        warehouse = warehouses.saveAndFlush(new Warehouse(DemoTenant.ID, "Berlin", "Berlin", "DE")).getId();
        first = products.saveAndFlush(new Product(DemoTenant.ID, "FIRST", "First product", BigDecimal.ONE)).getId();
        second = products.saveAndFlush(new Product(DemoTenant.ID, "SECOND", "Second product", BigDecimal.ONE)).getId();
        stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouse, first, 10));
        stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouse, second, 10));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void createsConfirmedOrderWithOwnedHoldAndLogsAfterCommit(CapturedOutput output) throws Exception {
        String location = create(body(UUID.randomUUID(), warehouse, " Customer-42 ", line(first, 3), line(second, 4)));
        mvc.perform(get(location)).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("CONFIRMED"))
                .andExpect(jsonPath("customerReference").value("Customer-42"))
                .andExpect(jsonPath("reservationId").exists())
                .andExpect(jsonPath("items.length()").value(2))
                .andExpect(jsonPath("tenantId").doesNotExist());
        var order = orders.findAll().getFirst();
        mvc.perform(get("/api/v1/warehouses/" + warehouse + "/reservations/" + order.getReservationId()))
                .andExpect(jsonPath("ownerOrderId").value(order.getId().toString()))
                .andExpect(jsonPath("status").value("ACTIVE"));
        held(first, 3);
        held(second, 4);
        assertTrue(output.getOut().contains("operation=ORDER_CONFIRMED"));
        assertFalse(output.getOut().contains("Customer-42"));
    }

    @Test
    void reorderedRetryReturnsExistingOrderWithoutAnotherHold() throws Exception {
        UUID request = UUID.randomUUID();
        String location = create(body(request, warehouse, "Customer", line(first, 10), line(second, 10)));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(body(request, warehouse, "Customer", line(second, 10), line(first, 10))))
                .andExpect(status().isOk());
        assertEquals(1, orders.count());
        assertEquals(1, reservations.count());
        held(first, 10);
        mvc.perform(post(location + "/cancel")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(body(request, warehouse, "Customer", line(first, 10), line(second, 10))))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("CANCELLED"));
        held(first, 0);
    }

    @Test
    void changedDetailsCannotReuseRequestId() throws Exception {
        UUID request = UUID.randomUUID();
        create(body(request, warehouse, "Customer", line(first, 3)));
        for (String changed : List.of(body(request, warehouse, "Different", line(first, 3)),
                body(request, UUID.randomUUID(), "Customer", line(first, 3)),
                body(request, warehouse, "Customer", line(second, 3)))) {
            mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(changed))
                    .andExpect(status().isConflict());
        }
        assertEquals(1, orders.count());
        held(first, 3);
        held(second, 0);
    }

    @Test
    void insufficientStockRollsBackOrderAndEveryItemHold() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), warehouse, "Customer", line(first, 3), line(second, 11))))
                .andExpect(status().isConflict());
        held(first, 0);
        held(second, 0);
        assertEquals(0, orders.count());
        assertEquals(0, orderItems.count());
        assertEquals(0, reservations.count());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void itemWriteFailureRollsBackOrderAndInventoryAndSuppressesSuccessLog(CapturedOutput output) throws Exception {
        jdbc.execute("ALTER TABLE customer_order_items ADD CONSTRAINT test_reject_order_quantity CHECK (quantity <> 3)");
        try {
            mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                            .content(body(UUID.randomUUID(), warehouse, "Customer", line(first, 3))))
                    .andExpect(status().isConflict());
            held(first, 0);
            assertEquals(0, orders.count());
            assertEquals(0, reservations.count());
            assertEquals(0, reservationItems.count());
            assertFalse(output.getOut().contains("operation=ORDER_CONFIRMED"));
            assertFalse(output.getOut().contains("operation=RESERVATION_CREATED"));
        } finally {
            jdbc.execute("ALTER TABLE customer_order_items DROP CONSTRAINT test_reject_order_quantity");
        }
    }

    @Test
    void cancellationReleasesOnlyItsOwnStockAndIsRetrySafe() throws Exception {
        String one = create(body(UUID.randomUUID(), warehouse, "Customer A", line(first, 3)));
        create(body(UUID.randomUUID(), warehouse, "Customer B", line(first, 4)));
        mvc.perform(post(one + "/cancel")).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("CANCELLED"))
                .andExpect(jsonPath("cancelledAt").exists());
        mvc.perform(post(one + "/cancel")).andExpect(status().isOk());
        held(first, 4);
        assertEquals(2, orders.count());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedCancellationRollsBackReservationRelease(CapturedOutput output) throws Exception {
        String location = create(body(UUID.randomUUID(), warehouse, "Customer", line(first, 3)));
        jdbc.execute("ALTER TABLE customer_orders ADD CONSTRAINT test_reject_cancel CHECK (status <> 'CANCELLED')");
        try {
            mvc.perform(post(location + "/cancel")).andExpect(status().isConflict());
            held(first, 3);
            assertEquals("ACTIVE", reservations.findAll().getFirst().getStatus());
            assertEquals("CONFIRMED", orders.findAll().getFirst().getStatus());
            assertFalse(output.getOut().contains("operation=ORDER_CANCELLED"));
            assertFalse(output.getOut().contains("operation=RESERVATION_RELEASED"));
        } finally {
            jdbc.execute("ALTER TABLE customer_orders DROP CONSTRAINT test_reject_cancel");
        }
    }

    @Test
    void standaloneCommandsCannotReleaseOrClaimAnOrdersHold() throws Exception {
        String location = create(body(UUID.randomUUID(), warehouse, "Customer", line(first, 3)));
        var order = orders.findAll().getFirst();
        String base = "/api/v1/warehouses/" + warehouse + "/reservations";
        mvc.perform(post(base + "/" + order.getReservationId() + "/release")).andExpect(status().isConflict());
        mvc.perform(post(base).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + order.getId() + "\",\"items\":[" + line(first, 3) + "]}"))
                .andExpect(status().isConflict());
        held(first, 3);
        mvc.perform(post(location + "/cancel")).andExpect(status().isOk());
        held(first, 0);
    }

    @Test
    void catalogEditsDoNotRewriteOrderItemSnapshots() throws Exception {
        String location = create(body(UUID.randomUUID(), warehouse, "Customer", line(first, 3)));
        jdbc.update("UPDATE products SET name = 'Renamed', sku = 'RENAMED' WHERE id = ?", first);
        mvc.perform(get(location)).andExpect(jsonPath("items[0].sku").value("FIRST"))
                .andExpect(jsonPath("items[0].productName").value("First product"));
    }

    @Test
    void listsWithBoundedPaginationAndEnforcesTenantScope() throws Exception {
        create(body(UUID.randomUUID(), warehouse, "Customer", line(first, 1)));
        UUID otherTenant = UUID.randomUUID();
        UUID otherWarehouse = warehouses.saveAndFlush(new Warehouse(otherTenant, "Private", "Berlin", "DE")).getId();
        UUID otherProduct = products.saveAndFlush(new Product(otherTenant, "PRIVATE", "Private", BigDecimal.ONE)).getId();
        stocks.saveAndFlush(new Stock(otherTenant, otherWarehouse, otherProduct, 10));
        var other = service.create(otherTenant, new CreateOrderRequest(UUID.randomUUID(), otherWarehouse,
                "Private customer", List.of(new CreateOrderRequest.Item(otherProduct, 1)))).response();
        mvc.perform(get("/api/v1/orders?page=0&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("totalElements").value(1))
                .andExpect(jsonPath("totalPages").value(1))
                .andExpect(jsonPath("content[0].customerReference").value("Customer"));
        mvc.perform(get("/api/v1/orders/" + other.id())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/" + other.id() + "/cancel")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders?page=1&size=1")).andExpect(jsonPath("content.length()").value(0));
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=invalid")) {
            mvc.perform(get("/api/v1/orders" + query)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void invalidInputAndUnknownReferencesLeaveNoOrderOrStockHold() throws Exception {
        for (String invalid : List.of("{}", body(UUID.randomUUID(), warehouse, " ", line(first, 1)),
                body(UUID.randomUUID(), warehouse, "Customer"),
                body(UUID.randomUUID(), warehouse, "Customer", line(first, 0)),
                body(UUID.randomUUID(), warehouse, "Customer", line(first, 1), line(first, 2)))) {
            mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        for (String missing : List.of(body(UUID.randomUUID(), UUID.randomUUID(), "Customer", line(first, 1)),
                body(UUID.randomUUID(), warehouse, "Customer", line(UUID.randomUUID(), 1)))) {
            mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(missing))
                    .andExpect(status().isNotFound());
        }
        assertEquals(0, orders.count());
        held(first, 0);
        mvc.perform(get("/api/v1/orders/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/" + UUID.randomUUID() + "/cancel")).andExpect(status().isNotFound());
    }

    @Test
    void concurrentIdenticalOrdersCreateExactlyOneHold() throws Exception {
        UUID request = UUID.randomUUID();
        assertEquals(List.of(200, 201), concurrently(createTask(body(request, warehouse, "Customer", line(first, 7))),
                createTask(body(request, warehouse, "Customer", line(first, 7)))));
        assertEquals(1, orders.count());
        assertEquals(1, reservations.count());
        held(first, 7);
    }

    @Test
    void concurrentDifferentOrdersCannotOversell() throws Exception {
        assertEquals(List.of(201, 409), concurrently(createTask(body(UUID.randomUUID(), warehouse, "A", line(first, 7))),
                createTask(body(UUID.randomUUID(), warehouse, "B", line(first, 7)))));
        assertEquals(1, orders.count());
        held(first, 7);
    }

    @Test
    void concurrentCancellationReleasesOnce() throws Exception {
        String location = create(body(UUID.randomUUID(), warehouse, "Customer", line(first, 7)));
        Callable<Integer> cancel = () -> mvc.perform(post(location + "/cancel")).andReturn().getResponse().getStatus();
        assertEquals(List.of(200, 200), concurrently(cancel, cancel));
        held(first, 0);
        assertEquals("CANCELLED", orders.findAll().getFirst().getStatus());
    }

    private String create(String body) throws Exception {
        return mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andReturn().getResponse().getHeader("Location");
    }

    private void held(UUID product, int quantity) {
        var stock = stocks.findByTenantIdAndWarehouseIdAndProductId(DemoTenant.ID, warehouse, product).orElseThrow();
        assertEquals(10, stock.getOnHand());
        assertEquals(quantity, stock.getReserved());
        assertEquals(10 - quantity, stock.getAvailable());
    }

    private Callable<Integer> createTask(String body) {
        return () -> mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
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

    private String body(UUID request, UUID location, String reference, String... lines) {
        return "{\"requestId\":\"" + request + "\",\"warehouseId\":\"" + location
                + "\",\"customerReference\":\"" + reference + "\",\"items\":[" + String.join(",", lines) + "]}";
    }
    private String line(UUID product, int quantity) {
        return "{\"productId\":\"" + product + "\",\"quantity\":" + quantity + "}";
    }
}
