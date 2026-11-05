package com.flowops.fulfillment;

import com.flowops.audit.SystemActor;
import com.flowops.inventory.entity.Stock;
import com.flowops.inventory.repository.StockRepository;
import com.flowops.inventory.service.ReservationService;
import com.flowops.product.entity.Product;
import com.flowops.product.repository.ProductRepository;
import com.flowops.order.service.OrderService;
import com.flowops.shipment.service.ShipmentService;
import com.flowops.support.AuthenticatedApiTestConfiguration;
import com.flowops.support.PostgresIntegrationTest;
import com.flowops.tenant.DemoTenant;
import com.flowops.warehouse.entity.Warehouse;
import com.flowops.warehouse.repository.WarehouseRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL locks and a controlled clock exercise expiry without timing-dependent sleeps. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({AuthenticatedApiTestConfiguration.class, ReservationExpiryTest.TimeFixture.class})
class ReservationExpiryTest extends PostgresIntegrationTest {
    static final MutableClock TIME = new MutableClock();
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return now.get(); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
    }
    @TestConfiguration
    static class TimeFixture {
        @Bean @Primary Clock expiryTestClock() { return TIME; }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ReservationExpiryWorker worker;
    @Autowired ReservationService inventory;
    @Autowired OrderService orders;
    @Autowired ShipmentService shipments;
    @Autowired WarehouseRepository warehouses;
    @Autowired ProductRepository products;
    @Autowired StockRepository stocks;
    UUID warehouse, first, second;

    @BeforeEach
    void prepareStock() {
        jdbc.execute("TRUNCATE shipment_status_events, shipments, customer_order_items, customer_orders, "
                + "inventory_reservation_items, inventory_reservations, stock_movements, stock, products, warehouses CASCADE");
        TIME.now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        warehouse = warehouses.saveAndFlush(new Warehouse(DemoTenant.ID, "Berlin", "Berlin", "DE")).getId();
        first = products.saveAndFlush(new Product(DemoTenant.ID, "FIRST", "First", BigDecimal.ONE)).getId();
        second = products.saveAndFlush(new Product(DemoTenant.ID, "SECOND", "Second", BigDecimal.ONE)).getId();
        stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouse, first, 10));
        stocks.saveAndFlush(new Stock(DemoTenant.ID, warehouse, second, 10));
    }

    @Test
    void standaloneExpiresExactlyAtDeadlineAndRetriesNeverReopenIt() throws Exception {
        UUID request = UUID.randomUUID();
        String body = holdBody(request);
        var hold = create("/api/v1/warehouses/" + warehouse + "/reservations", body);
        assertEquals(TIME.instant().plus(Duration.ofMinutes(30)), OffsetDateTime.parse(hold.path("expiresAt").asText()).toInstant());
        TIME.advance(Duration.ofMinutes(30).minusNanos(1));
        assertEquals(0, worker.runBatch());
        balance(first, 10, 2); balance(second, 10, 3);
        TIME.advance(Duration.ofNanos(1));
        assertEquals(1, worker.runBatch());
        assertEquals(0, worker.runBatch());
        balance(first, 10, 0); balance(second, 10, 0);
        mvc.perform(post("/api/v1/warehouses/" + warehouse + "/reservations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("EXPIRED"))
                .andExpect(jsonPath("expiredAt").isNotEmpty()).andExpect(jsonPath("releasedAt").isEmpty());
        mvc.perform(post("/api/v1/warehouses/" + warehouse + "/reservations/" + hold.path("id").asText() + "/release"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("EXPIRED"));
        balance(first, 10, 0);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void orderExpiryCancelsPreparedShipmentAtomicallyAndRecordsSystemActor(CapturedOutput output) throws Exception {
        UUID request = UUID.randomUUID();
        String body = orderBody(request);
        var order = create("/api/v1/orders", body);
        var shipment = prepare(order);
        TIME.advance(Duration.ofMinutes(30));
        assertEquals(1, worker.runBatch());
        mvc.perform(get("/api/v1/orders/" + order.path("id").asText())).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("EXPIRED")).andExpect(jsonPath("expiredAt").isNotEmpty())
                .andExpect(jsonPath("cancelledAt").isEmpty());
        mvc.perform(get("/api/v1/shipments/" + shipment.path("id").asText() + "/history"))
                .andExpect(jsonPath("$[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$[1].note").value("Reservation expired before dispatch"))
                .andExpect(jsonPath("$[1].actorIssuer").value("urn:flowops:system"))
                .andExpect(jsonPath("$[1].actorSubject").value("reservation-expiry"))
                .andExpect(jsonPath("$[1].actorName").value("Automatic reservation expiry"));
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("EXPIRED"));
        mvc.perform(post("/api/v1/shipments/" + shipment.path("id").asText() + "/dispatch"))
                .andExpect(status().isConflict());
        balance(first, 10, 0); balance(second, 10, 0);
        var cancellationAt = jdbc.queryForObject("SELECT created_at FROM shipment_status_events WHERE status='CANCELLED'", OffsetDateTime.class);
        assertEquals(TIME.instant(), cancellationAt.toInstant());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM stock_movements", Integer.class));
        assertTrue(output.getAll().contains("operation=RESERVATION_EXPIRED"));
        assertTrue(output.getAll().contains("operation=ORDER_EXPIRED"));
        // The worker's thread-local identity must not leak into the next real operator command.
        var next = create("/api/v1/orders", orderBody(UUID.randomUUID()));
        var prepared = prepare(next);
        mvc.perform(get("/api/v1/shipments/" + prepared.path("id").asText() + "/history"))
                .andExpect(jsonPath("$[0].actorSubject").value("test-operator"));
    }

    @Test
    void overdueHoldCannotBePreparedOrDispatchedWhileCleanupIsDisabled() throws Exception {
        var order = create("/api/v1/orders", orderBody(UUID.randomUUID()));
        var prepared = prepare(order);
        var unprepared = create("/api/v1/orders", orderBody(UUID.randomUUID()));
        TIME.advance(Duration.ofMinutes(30));
        mvc.perform(post("/api/v1/shipments/" + prepared.path("id").asText() + "/dispatch"))
                .andExpect(status().isConflict()).andExpect(jsonPath("error").value(org.hamcrest.Matchers.containsString("deadline")));
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId":"%s","carrier":"Demo"}
                """.formatted(unprepared.path("id").asText()))).andExpect(status().isConflict());
        balance(first, 10, 4); balance(second, 10, 6);
        assertEquals(2, worker.runBatch());
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId":"%s","carrier":"Demo"}
                """.formatted(order.path("id").asText()))).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("CANCELLED"));
        balance(first, 10, 0); balance(second, 10, 0);
    }

    @Test
    void consumedCancelledReleasedAndLegacyHoldsAreNeverExpired() throws Exception {
        var dispatched = create("/api/v1/orders", orderBody(UUID.randomUUID()));
        var shipment = prepare(dispatched);
        mvc.perform(post("/api/v1/shipments/" + shipment.path("id").asText() + "/dispatch")).andExpect(status().isOk());
        var cancelled = create("/api/v1/orders", orderBody(UUID.randomUUID()));
        mvc.perform(post("/api/v1/orders/" + cancelled.path("id").asText() + "/cancel")).andExpect(status().isOk());
        var released = create("/api/v1/warehouses/" + warehouse + "/reservations", holdBody(UUID.randomUUID()));
        mvc.perform(post("/api/v1/warehouses/" + warehouse + "/reservations/" + released.path("id").asText() + "/release"))
                .andExpect(status().isOk());
        var legacy = create("/api/v1/orders", orderBody(UUID.randomUUID()));
        jdbc.update("UPDATE inventory_reservations SET expires_at=NULL WHERE id=?", UUID.fromString(legacy.path("reservationId").asText()));
        jdbc.update("UPDATE customer_orders SET reservation_expires_at=NULL WHERE id=?", UUID.fromString(legacy.path("id").asText()));
        TIME.advance(Duration.ofDays(2));
        assertEquals(0, worker.runBatch());
        balance(first, 8, 2); balance(second, 7, 3);
        // A successful old dispatch remains replayable after its original deadline.
        mvc.perform(post("/api/v1/shipments/" + shipment.path("id").asText() + "/dispatch")).andExpect(status().isOk());
        balance(first, 8, 2);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedCandidateRollsBackAllItsChangesAndDoesNotBlockOtherCandidates(CapturedOutput output) throws Exception {
        var bad = create("/api/v1/orders", orderBody(UUID.randomUUID()));
        var shipment = prepare(bad);
        var good = create("/api/v1/warehouses/" + warehouse + "/reservations", holdBody(UUID.randomUUID()));
        TIME.advance(Duration.ofMinutes(30));
        jdbc.execute("ALTER TABLE shipment_status_events ADD CONSTRAINT test_reject_expiry CHECK (status <> 'CANCELLED')");
        try {
            assertEquals(1, worker.runBatch());
            mvc.perform(get("/api/v1/orders/" + bad.path("id").asText())).andExpect(jsonPath("status").value("CONFIRMED"));
            mvc.perform(get("/api/v1/shipments/" + shipment.path("id").asText())).andExpect(jsonPath("status").value("PREPARED"));
            assertEquals("EXPIRED", jdbc.queryForObject("SELECT status FROM inventory_reservations WHERE id=?", String.class,
                    UUID.fromString(good.path("id").asText())));
            balance(first, 10, 2); balance(second, 10, 3);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM shipment_status_events", Integer.class));
            assertFalse(output.getAll().contains("operation=ORDER_EXPIRED"));
            assertTrue(output.getAll().contains("Reservation expiry failed:"));
        } finally {
            jdbc.execute("ALTER TABLE shipment_status_events DROP CONSTRAINT test_reject_expiry");
        }
        assertEquals(1, worker.runBatch());
        balance(first, 10, 0); balance(second, 10, 0);
    }

    @Test
    void duplicateWorkersAndManualCancellationReleaseOnlyOnce() throws Exception {
        var order = create("/api/v1/orders", orderBody(UUID.randomUUID()));
        prepare(order);
        TIME.advance(Duration.ofMinutes(30));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var firstWorker = executor.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return worker.runBatch(); });
            var secondWorker = executor.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return worker.runBatch(); });
            var cancellation = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mvc.perform(post("/api/v1/orders/" + order.path("id").asText() + "/cancel")).andReturn().getResponse().getStatus();
            });
            start.countDown();
            int expired = firstWorker.get(15, TimeUnit.SECONDS) + secondWorker.get(15, TimeUnit.SECONDS);
            assertTrue(List.of(200, 409).contains(cancellation.get(15, TimeUnit.SECONDS)));
            assertTrue(expired == 0 || expired == 1);
        }
        balance(first, 10, 0); balance(second, 10, 0);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM shipment_status_events", Integer.class));
        assertEquals(0, worker.runBatch());
    }

    @Test
    void expiryAndLateDispatchCannotBothWin() throws Exception {
        var order = create("/api/v1/orders", orderBody(UUID.randomUUID()));
        var shipment = prepare(order);
        TIME.advance(Duration.ofMinutes(30));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var expiry = executor.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return worker.runBatch(); });
            var dispatch = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mvc.perform(post("/api/v1/shipments/" + shipment.path("id").asText() + "/dispatch")).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertEquals(1, expiry.get(15, TimeUnit.SECONDS));
            assertEquals(409, dispatch.get(15, TimeUnit.SECONDS));
        }
        balance(first, 10, 0); balance(second, 10, 0);
    }

    @Test
    void v10UpgradeKeepsOldOrdersAndHoldsWithoutDeadlines() {
        try {
            Flyway.configure().dataSource(dataSource).schemas("expiry_upgrade").defaultSchema("expiry_upgrade").target("10").load().migrate();
            UUID hold = UUID.randomUUID(), order = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO expiry_upgrade.inventory_reservations
                    (id,tenant_id,warehouse_id,request_id,status,created_at,owner_order_id)
                    VALUES (?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP,?)
                    """, hold, DemoTenant.ID, warehouse, order, order);
            jdbc.update("""
                    INSERT INTO expiry_upgrade.customer_orders
                    (id,tenant_id,request_id,warehouse_id,customer_reference,status,reservation_id,created_at)
                    VALUES (?,?,?,?,'Legacy','CONFIRMED',?,CURRENT_TIMESTAMP)
                    """, order, DemoTenant.ID, UUID.randomUUID(), warehouse, hold);
            Flyway.configure().dataSource(dataSource).schemas("expiry_upgrade").defaultSchema("expiry_upgrade").load().migrate();
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM expiry_upgrade.inventory_reservations WHERE status='ACTIVE' AND expires_at IS NULL", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM expiry_upgrade.customer_orders WHERE status='CONFIRMED' AND reservation_expires_at IS NULL AND expired_at IS NULL", Integer.class));
        } finally { jdbc.execute("DROP SCHEMA IF EXISTS expiry_upgrade CASCADE"); }
    }

    @Test
    void workerScopesEachCandidateToItsTenantAndLeavesLaterDeadlinesHeld() throws Exception {
        UUID tenantB = UUID.randomUUID();
        UUID warehouseB = warehouses.saveAndFlush(new Warehouse(tenantB, "Hamburg", "Hamburg", "DE")).getId();
        UUID productB = products.saveAndFlush(new Product(tenantB, "OTHER", "Other", BigDecimal.ONE)).getId();
        stocks.saveAndFlush(new Stock(tenantB, warehouseB, productB, 5));
        inventory.reserve(tenantB, warehouseB, new com.flowops.inventory.dto.CreateReservationRequest(UUID.randomUUID(),
                List.of(new com.flowops.inventory.dto.CreateReservationRequest.Item(productB, 4))));
        TIME.advance(Duration.ofMinutes(15));
        create("/api/v1/warehouses/" + warehouse + "/reservations", holdBody(UUID.randomUUID()));
        TIME.advance(Duration.ofMinutes(15));
        assertEquals(1, worker.runBatch());
        var otherStock = stocks.findByTenantIdAndWarehouseIdAndProductId(tenantB, warehouseB, productB).orElseThrow();
        assertEquals(5, otherStock.getOnHand()); assertEquals(0, otherStock.getReserved());
        balance(first, 10, 2); balance(second, 10, 3);
    }

    @Test
    void boundedBatchesMakeProgressWithoutReleasingAnyHoldTwice() throws Exception {
        for (int i = 0; i < 3; i++) create("/api/v1/warehouses/" + warehouse + "/reservations", holdBody(UUID.randomUUID()));
        TIME.advance(Duration.ofMinutes(30));
        var bounded = new ReservationExpiryWorker(inventory, orders, TIME, 1);
        assertEquals(1, bounded.runBatch());
        balance(first, 10, 4); balance(second, 10, 6);
        assertEquals(1, bounded.runBatch());
        assertEquals(1, bounded.runBatch());
        assertEquals(0, bounded.runBatch());
        balance(first, 10, 0); balance(second, 10, 0);
    }

    private String holdBody(UUID request) {
        return """
                {"requestId":"%s","expiresAt":"2099-01-01T00:00:00Z","items":[{"productId":"%s","quantity":2},{"productId":"%s","quantity":3}]}
                """.formatted(request, first, second);
    }
    private String orderBody(UUID request) {
        return """
                {"requestId":"%s","warehouseId":"%s","customerReference":"Expiry QA","items":[{"productId":"%s","quantity":2},{"productId":"%s","quantity":3}]}
                """.formatted(request, warehouse, first, second);
    }
    private JsonNode prepare(JsonNode order) throws Exception {
        return create("/api/v1/shipments", """
                {"orderId":"%s","carrier":"Demo"}
                """.formatted(order.path("id").asText()));
    }
    private JsonNode create(String endpoint, String body) throws Exception {
        return json.readTree(mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }
    private void balance(UUID product, int onHand, int reserved) {
        var stock = stocks.findByTenantIdAndWarehouseIdAndProductId(DemoTenant.ID, warehouse, product).orElseThrow();
        assertEquals(onHand, stock.getOnHand()); assertEquals(reserved, stock.getReserved());
    }
}
