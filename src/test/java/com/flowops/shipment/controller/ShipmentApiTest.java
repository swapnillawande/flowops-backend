package com.flowops.shipment.controller;

import com.flowops.inventory.entity.Stock;
import com.flowops.inventory.repository.*;
import com.flowops.order.dto.CreateOrderRequest;
import com.flowops.order.repository.*;
import com.flowops.order.service.OrderService;
import com.flowops.product.entity.Product;
import com.flowops.product.repository.ProductRepository;
import com.flowops.shipment.dto.CreateShipmentRequest;
import com.flowops.shipment.repository.ShipmentRepository;
import com.flowops.shipment.repository.ShipmentStatusEventRepository;
import com.flowops.shipment.service.ShipmentService;
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

import org.flywaydb.core.Flyway;
import javax.sql.DataSource;
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

/** PostgreSQL integration tests cover the complete order -> hold -> shipment -> stock deduction transaction. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AuthenticatedApiTestConfiguration.class)
class ShipmentApiTest extends PostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ShipmentRepository shipments;
    @Autowired ShipmentStatusEventRepository history;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository orderItems;
    @Autowired ReservationRepository reservations;
    @Autowired ReservationItemRepository reservationItems;
    @Autowired StockRepository stocks;
    @Autowired StockMovementRepository movements;
    @Autowired WarehouseRepository warehouses;
    @Autowired ProductRepository products;
    @Autowired OrderService orderService;
    @Autowired ShipmentService shipmentService;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;

    private UUID warehouse;
    private UUID first;
    private UUID second;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        history.deleteAllInBatch();
        shipments.deleteAllInBatch();
        orderItems.deleteAllInBatch();
        orders.deleteAllInBatch();
        reservationItems.deleteAllInBatch();
        reservations.deleteAllInBatch();
        movements.deleteAllInBatch();
        stocks.deleteAllInBatch();
        products.deleteAllInBatch();
        warehouses.deleteAllInBatch();
        warehouse = warehouses.saveAndFlush(new Warehouse(DemoTenant.ID, "Berlin", "Berlin", "DE")).getId();
        first = products.saveAndFlush(new Product(DemoTenant.ID, "FIRST", "First", BigDecimal.ONE)).getId();
        second = products.saveAndFlush(new Product(DemoTenant.ID, "SECOND", "Second", BigDecimal.ONE)).getId();
        stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouse, first, 10));
        stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouse, second, 10));
        orderId = newOrder(DemoTenant.ID, warehouse, first, second);
    }

    @Test
    void preparationKeepsHoldAndGeneratesTrackingNumber() throws Exception {
        String location = prepare();
        mvc.perform(get(location)).andExpect(status().isOk())
                .andExpect(jsonPath("orderId").value(orderId.toString()))
                .andExpect(jsonPath("warehouseId").value(warehouse.toString()))
                .andExpect(jsonPath("carrier").value("Demo carrier"))
                .andExpect(jsonPath("status").value("PREPARED"))
                .andExpect(jsonPath("trackingNumber").isString())
                .andExpect(jsonPath("tenantId").doesNotExist());
        assertTrue(shipments.findAll().getFirst().getTrackingNumber().startsWith("FLO-"));
        balance(first, 10, 3);
        assertEquals(0, movements.count());
    }

    @Test
    void duplicateCreationReplaysAndChangedCarrierConflicts() throws Exception {
        prepare();
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(body(orderId, "Demo carrier")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(body(orderId, "Another carrier")))
                .andExpect(status().isConflict());
        assertEquals(1, shipments.count());
        balance(first, 10, 3);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void dispatchCommitsAllStatesAndAuditEntries(CapturedOutput output) throws Exception {
        String location = prepare();
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("DISPATCHED"))
                .andExpect(jsonPath("dispatchedAt").exists());
        balance(first, 7, 0);
        balance(second, 6, 0);
        var order = orders.findById(orderId).orElseThrow();
        assertEquals("DISPATCHED", order.getStatus());
        assertNotNull(order.getDispatchedAt());
        var hold = reservations.findById(order.getReservationId()).orElseThrow();
        assertEquals("CONSUMED", hold.getStatus());
        assertNotNull(hold.getConsumedAt());
        assertEquals(shipments.findAll().getFirst().getId(), hold.getConsumedShipmentId());
        assertEquals(2, movements.count());
        var movement = movements.findAll().stream().filter(m -> m.getQuantity() == -3).findFirst().orElseThrow();
        assertEquals("DISPATCH", movement.getType());
        assertEquals(10, movement.getOnHandBefore());
        assertEquals(7, movement.getOnHandAfter());
        assertEquals(hold.getConsumedShipmentId(), movement.getShipmentId());
        assertTrue(output.getOut().contains("operation=SHIPMENT_DISPATCHED"));
        assertTrue(output.getOut().contains("operation=ORDER_DISPATCHED"));
    }

    @Test
    void dispatchPreservesOtherOrdersHoldsAndRetriesDoNotDeductAgain() throws Exception {
        newOrder(DemoTenant.ID, warehouse, first, second);
        String location = prepare();
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk());
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk());
        balance(first, 7, 3);
        balance(second, 6, 4);
        assertEquals(2, movements.count());
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(body(orderId, "Demo carrier")))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("DISPATCHED"));
    }

    @Test
    void cancellingPreparedShipmentReleasesHoldAndPreventsDispatch() throws Exception {
        String location = prepare();
        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")).andExpect(status().isOk());
        mvc.perform(get(location)).andExpect(jsonPath("status").value("CANCELLED"))
                .andExpect(jsonPath("cancelledAt").exists());
        mvc.perform(post(location + "/dispatch")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(body(orderId, "Demo carrier")))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("CANCELLED"));
        balance(first, 10, 0);
        assertEquals(0, movements.count());
    }

    @Test
    void dispatchedOrderAndConsumedHoldCannotBeCancelledOrReleased() throws Exception {
        String location = prepare();
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")).andExpect(status().isConflict());
        UUID hold = orders.findById(orderId).orElseThrow().getReservationId();
        mvc.perform(post("/api/v1/warehouses/" + warehouse + "/reservations/" + hold + "/release"))
                .andExpect(status().isConflict());
        balance(first, 7, 0);
        assertEquals("DISPATCHED", orders.findById(orderId).orElseThrow().getStatus());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void auditFailureRollsBackStockAndEveryLifecycle(CapturedOutput output) throws Exception {
        String location = prepare();
        jdbc.execute("ALTER TABLE stock_movements ADD CONSTRAINT test_no_dispatch CHECK (type <> 'DISPATCH')");
        try {
            mvc.perform(post(location + "/dispatch")).andExpect(status().isConflict());
            assertPrepared();
            balance(first, 10, 3);
            balance(second, 10, 4);
            assertEquals(0, movements.count());
            assertFalse(output.getOut().contains("operation=SHIPMENT_DISPATCHED"));
            assertFalse(output.getOut().contains("operation=ORDER_DISPATCHED"));
            assertFalse(output.getOut().contains("operation=RESERVATION_CONSUMED"));
        } finally {
            jdbc.execute("ALTER TABLE stock_movements DROP CONSTRAINT test_no_dispatch");
        }
    }

    @Test
    void shipmentWriteFailureRollsBackInventoryConsumption() throws Exception {
        String location = prepare();
        jdbc.execute("ALTER TABLE shipments ADD CONSTRAINT test_no_dispatch CHECK (status <> 'DISPATCHED')");
        try {
            mvc.perform(post(location + "/dispatch")).andExpect(status().isConflict());
            assertPrepared();
            balance(first, 10, 3);
            assertEquals(0, movements.count());
        } finally {
            jdbc.execute("ALTER TABLE shipments DROP CONSTRAINT test_no_dispatch");
        }
    }

    @Test
    void shipmentCancellationFailureRollsBackOrderCancellationAndRelease() throws Exception {
        prepare();
        jdbc.execute("ALTER TABLE shipments ADD CONSTRAINT test_no_cancel CHECK (status <> 'CANCELLED')");
        try {
            mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")).andExpect(status().isConflict());
            assertPrepared();
            balance(first, 10, 3);
        } finally {
            jdbc.execute("ALTER TABLE shipments DROP CONSTRAINT test_no_cancel");
        }
    }

    @Test
    void publicMovementRetryKeysCannotBlockDispatchAudit() throws Exception {
        String location = prepare();
        UUID shipmentId = shipments.findAll().getFirst().getId();
        mvc.perform(post("/api/v1/warehouses/" + warehouse + "/stock/" + first + "/movements")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + shipmentId
                                + "\",\"type\":\"RECEIPT\",\"quantity\":1,\"reason\":\"Delivery\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk());
        balance(first, 8, 0);
        assertEquals(3, movements.count());
    }

    @Test
    void scopesLookupDispatchAndPaginationToTenant() throws Exception {
        prepare();
        UUID otherTenant = UUID.randomUUID();
        UUID otherWarehouse = warehouses.saveAndFlush(new Warehouse(otherTenant, "Private", "Berlin", "DE")).getId();
        UUID otherFirst = products.saveAndFlush(new Product(otherTenant, "ONE", "One", BigDecimal.ONE)).getId();
        UUID otherSecond = products.saveAndFlush(new Product(otherTenant, "TWO", "Two", BigDecimal.ONE)).getId();
        stocks.saveAndFlush(new Stock(otherTenant, otherWarehouse, otherFirst, 10));
        stocks.saveAndFlush(new Stock(otherTenant, otherWarehouse, otherSecond, 10));
        UUID otherOrder = newOrder(otherTenant, otherWarehouse, otherFirst, otherSecond);
        var other = shipmentService.create(otherTenant, new CreateShipmentRequest(otherOrder, "Private carrier")).response();
        mvc.perform(get("/api/v1/shipments/" + other.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/shipments/" + other.id() + "/history")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/shipments/" + other.id() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(statusBody(UUID.randomUUID(), "IN_TRANSIT", "Private"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/shipments/" + other.id() + "/dispatch")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(body(otherOrder, "Private carrier")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/shipments?page=0&size=1")).andExpect(status().isOk())
                .andExpect(jsonPath("totalElements").value(1)).andExpect(jsonPath("content.length()").value(1));
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=bad")) {
            mvc.perform(get("/api/v1/shipments" + query)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void rejectsInvalidMissingAndCancelledOrderInput() throws Exception {
        for (String invalid : List.of("{}", body(orderId, " "), body(orderId, "X".repeat(81)))) {
            mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID(), "Carrier")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(body(orderId, "Carrier")))
                .andExpect(status().isConflict());
        assertEquals(0, shipments.count());
    }

    @Test
    void concurrentPreparationCreatesOneShipment() throws Exception {
        Callable<Integer> create = () -> mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON)
                .content(body(orderId, "Demo carrier"))).andReturn().getResponse().getStatus();
        assertEquals(List.of(200, 201), concurrently(create, create));
        assertEquals(1, shipments.count());
    }

    @Test
    void concurrentDispatchDeductsOnlyOnce() throws Exception {
        String location = prepare();
        Callable<Integer> dispatch = () -> mvc.perform(post(location + "/dispatch")).andReturn().getResponse().getStatus();
        assertEquals(List.of(200, 200), concurrently(dispatch, dispatch));
        balance(first, 7, 0);
        assertEquals(2, movements.count());
    }

    @Test
    void dispatchAndCancellationRaceHasExactlyOneWinner() throws Exception {
        String location = prepare();
        Callable<Integer> dispatch = () -> mvc.perform(post(location + "/dispatch")).andReturn().getResponse().getStatus();
        Callable<Integer> cancel = () -> mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")).andReturn().getResponse().getStatus();
        assertEquals(List.of(200, 409), concurrently(dispatch, cancel));
        var shipment = shipments.findAll().getFirst();
        var order = orders.findById(orderId).orElseThrow();
        assertEquals(order.getStatus(), shipment.getStatus());
        if ("DISPATCHED".equals(shipment.getStatus())) {
            balance(first, 7, 0);
            assertEquals(2, movements.count());
        } else {
            assertEquals("CANCELLED", shipment.getStatus());
            balance(first, 10, 0);
            assertEquals(0, movements.count());
        }
    }

    @Test
    void migrationPreservesConfirmedOrdersAndActiveHolds() {
        try {
            Flyway.configure().dataSource(dataSource).schemas("shipping_upgrade").defaultSchema("shipping_upgrade")
                    .target("7").load().migrate();
            UUID oldHold = UUID.randomUUID();
            UUID oldOrder = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO shipping_upgrade.inventory_reservations
                    (id, tenant_id, warehouse_id, request_id, status, created_at, owner_order_id)
                    VALUES (?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, ?)
                    """, oldHold, DemoTenant.ID, warehouse, oldOrder, oldOrder);
            jdbc.update("""
                    INSERT INTO shipping_upgrade.customer_orders
                    (id, tenant_id, request_id, warehouse_id, customer_reference, status, reservation_id, created_at)
                    VALUES (?, ?, ?, ?, 'Existing customer', 'CONFIRMED', ?, CURRENT_TIMESTAMP)
                    """, oldOrder, DemoTenant.ID, UUID.randomUUID(), warehouse, oldHold);
            jdbc.update("""
                    INSERT INTO shipping_upgrade.stock
                    (id, tenant_id, warehouse_id, product_id, on_hand, reserved, version, created_at)
                    VALUES (?, ?, ?, ?, 10, 3, 0, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID(), DemoTenant.ID, warehouse, first);
            Flyway.configure().dataSource(dataSource).schemas("shipping_upgrade").defaultSchema("shipping_upgrade")
                    .load().migrate();
            assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM shipping_upgrade.customer_orders", String.class));
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM shipping_upgrade.inventory_reservations", String.class));
            assertEquals(oldOrder, jdbc.queryForObject("SELECT owner_order_id FROM shipping_upgrade.inventory_reservations", UUID.class));
            assertEquals(3, jdbc.queryForObject("SELECT reserved FROM shipping_upgrade.stock", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM shipping_upgrade.shipments", Integer.class));
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS shipping_upgrade CASCADE");
        }
    }

    @Test
    void deliveryProgressionUpdatesOrderAndRetainsStockAndHistory() throws Exception {
        String location = prepare();
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk());
        UUID transit = UUID.randomUUID();
        String transitBody = statusBody(transit, "IN_TRANSIT", "Departed hub");
        String original = mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON).content(transitBody))
                .andExpect(status().isOk()).andExpect(jsonPath("sequence").value(3))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody(UUID.randomUUID(), "DELIVERED", "Delivered to recipient")))
                .andExpect(status().isOk()).andExpect(jsonPath("sequence").value(4));
        mvc.perform(get(location)).andExpect(jsonPath("status").value("DELIVERED"))
                .andExpect(jsonPath("deliveredAt").exists());
        mvc.perform(get(location + "/history")).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].status").value("PREPARED"))
                .andExpect(jsonPath("$[3].status").value("DELIVERED"));
        assertEquals("DELIVERED", orders.findById(orderId).orElseThrow().getStatus());
        balance(first, 7, 0);
        assertEquals(2, movements.count());
        mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON).content(transitBody))
                .andExpect(status().isOk()).andExpect(content().json(original));
        // A delayed retry of dispatch must not deduct again after transit or delivery.
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk()).andExpect(jsonPath("status").value("DELIVERED"));
        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")).andExpect(status().isConflict());
        balance(first, 7, 0);
    }

    @Test
    void rejectsSkippedStatusesAndChangedRetryPayload() throws Exception {
        String location = prepare();
        mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(statusBody(UUID.randomUUID(), "IN_TRANSIT", "Too early"))).andExpect(status().isConflict());
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk());
        mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(statusBody(UUID.randomUUID(), "DELIVERED", "Cannot skip transit"))).andExpect(status().isConflict());
        UUID request = UUID.randomUUID();
        mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(statusBody(request, "IN_TRANSIT", "Departed"))).andExpect(status().isOk());
        mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(statusBody(request, "IN_TRANSIT", "Changed note"))).andExpect(status().isConflict());
        mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(statusBody(UUID.randomUUID(), "IN_TRANSIT", "Duplicate state"))).andExpect(status().isConflict());
        assertEquals(3, history.count());
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void historyFailureRollsBackDeliveryAndSuppressesSuccessLog(CapturedOutput output) throws Exception {
        String location = prepare();
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk());
        mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(statusBody(UUID.randomUUID(), "IN_TRANSIT", "Departed"))).andExpect(status().isOk());
        jdbc.execute("ALTER TABLE shipment_status_events ADD CONSTRAINT test_no_delivery CHECK (status <> 'DELIVERED')");
        try {
            mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON)
                    .content(statusBody(UUID.randomUUID(), "DELIVERED", "Delivered"))).andExpect(status().isConflict());
            assertEquals("DISPATCHED", orders.findById(orderId).orElseThrow().getStatus());
            assertEquals("IN_TRANSIT", shipments.findAll().getFirst().getStatus());
            assertEquals(3, history.count());
            assertFalse(output.getOut().contains("operation=ORDER_DELIVERED"));
            assertFalse(output.getOut().contains("operation=SHIPMENT_DELIVERED"));
        } finally { jdbc.execute("ALTER TABLE shipment_status_events DROP CONSTRAINT test_no_delivery"); }
    }

    @Test
    void concurrentStatusRetriesWriteOneEvent() throws Exception {
        String location = prepare();
        mvc.perform(post(location + "/dispatch")).andExpect(status().isOk());
        String body = statusBody(UUID.randomUUID(), "IN_TRANSIT", "Departed");
        Callable<Integer> update = () -> mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn().getResponse().getStatus();
        assertEquals(List.of(200, 200), concurrently(update, update));
        assertEquals(3, history.count());
    }

    @Test
    void statusValidationAndUnknownHistoryAreSafe() throws Exception {
        String location = prepare();
        for (String invalid : List.of("{}", statusBody(UUID.randomUUID(), "DELIVERED", " "),
                statusBody(UUID.randomUUID(), "PREPARED", "Wrong command"),
                statusBody(UUID.randomUUID(), "IN_TRANSIT", "X".repeat(251)))) {
            mvc.perform(post(location + "/status").contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/shipments/" + UUID.randomUUID() + "/history")).andExpect(status().isNotFound());
        assertEquals(1, history.count());
    }

    @Test
    void migrationBackfillsPreparedAndDispatchedHistory() {
        try {
            Flyway.configure().dataSource(dataSource).schemas("history_upgrade").defaultSchema("history_upgrade")
                    .target("8").load().migrate();
            UUID oldShipment = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO history_upgrade.shipments
                    (id, tenant_id, order_id, warehouse_id, tracking_number, carrier, status, created_at, dispatched_at)
                    VALUES (?, ?, ?, ?, 'OLD-TRACK', 'Carrier', 'DISPATCHED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, oldShipment, DemoTenant.ID, UUID.randomUUID(), warehouse);
            Flyway.configure().dataSource(dataSource).schemas("history_upgrade").defaultSchema("history_upgrade").load().migrate();
            assertEquals(List.of("PREPARED", "DISPATCHED"), jdbc.queryForList(
                    "SELECT status FROM history_upgrade.shipment_status_events ORDER BY sequence", String.class));
            assertEquals("DISPATCHED", jdbc.queryForObject("SELECT status FROM history_upgrade.shipments", String.class));
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS history_upgrade CASCADE"); }
    }

    private String statusBody(UUID request, String status, String note) {
        return "{\"requestId\":\"" + request + "\",\"status\":\"" + status + "\",\"note\":\"" + note + "\"}";
    }

    private UUID newOrder(UUID tenant, UUID location, UUID one, UUID two) {
        return orderService.create(tenant, new CreateOrderRequest(UUID.randomUUID(), location, "Demo customer",
                List.of(new CreateOrderRequest.Item(one, 3), new CreateOrderRequest.Item(two, 4)))).response().id();
    }
    private String prepare() throws Exception {
        return mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content(body(orderId, " Demo carrier ")))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andReturn().getResponse().getHeader("Location");
    }
    private String body(UUID order, String carrier) { return "{\"orderId\":\"" + order + "\",\"carrier\":\"" + carrier + "\"}"; }
    private void balance(UUID product, int onHand, int reserved) {
        var stock = stocks.findByTenantIdAndWarehouseIdAndProductId(DemoTenant.ID, warehouse, product).orElseThrow();
        assertEquals(onHand, stock.getOnHand());
        assertEquals(reserved, stock.getReserved());
        assertEquals(onHand - reserved, stock.getAvailable());
    }
    private void assertPrepared() {
        assertEquals("PREPARED", shipments.findAll().getFirst().getStatus());
        var order = orders.findById(orderId).orElseThrow();
        assertEquals("CONFIRMED", order.getStatus());
        assertEquals("ACTIVE", reservations.findById(order.getReservationId()).orElseThrow().getStatus());
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
}
