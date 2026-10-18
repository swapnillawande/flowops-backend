package com.flowops.inventory.service;

import com.flowops.inventory.dto.CreateReservationRequest;
import com.flowops.inventory.dto.ReservationResponse;
import com.flowops.inventory.entity.InventoryReservation;
import com.flowops.inventory.entity.ReservationItem;
import com.flowops.inventory.entity.Stock;
import com.flowops.inventory.event.InventoryActivity;
import com.flowops.inventory.exception.ReservationConflictException;
import com.flowops.inventory.exception.ReservationNotFoundException;
import com.flowops.inventory.exception.StockNotFoundException;
import com.flowops.inventory.repository.ReservationItemRepository;
import com.flowops.inventory.repository.ReservationRepository;
import com.flowops.inventory.repository.StockRepository;
import com.flowops.warehouse.service.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Coordinates all-or-nothing holds. Physical on-hand stock changes only during later fulfillment. */
@Service
public class ReservationService {
    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private final ReservationRepository reservations;
    private final ReservationItemRepository items;
    private final StockRepository stocks;
    private final WarehouseService warehouses;
    private final ApplicationEventPublisher events;

    public ReservationService(ReservationRepository reservations, ReservationItemRepository items,
                              StockRepository stocks, WarehouseService warehouses, ApplicationEventPublisher events) {
        this.reservations = reservations;
        this.items = items;
        this.stocks = stocks;
        this.warehouses = warehouses;
        this.events = events;
    }

    public record Result(ReservationResponse response, boolean created) {}

    /** Lock every stock row before checking availability; any failure rolls back the whole hold. */
    @Transactional
    public Result reserve(UUID tenantId, UUID warehouseId, CreateReservationRequest request) {
        warehouses.getWarehouseById(tenantId, warehouseId);
        Map<UUID, Integer> requested = new TreeMap<>();
        for (var item : request.items()) {
            if (requested.put(item.productId(), item.quantity()) != null) {
                throw new IllegalArgumentException("Each product must appear only once in a reservation");
            }
        }
        Map<UUID, Stock> locked = lockStocks(tenantId, warehouseId, requested);

        // Check retries before availability: an existing hold may already have used the final free unit.
        var previous = reservations.findByTenantIdAndWarehouseIdAndRequestId(tenantId, warehouseId, request.requestId());
        if (previous.isPresent()) {
            var existing = previous.get();
            var originalItems = items.findByTenantIdAndReservationIdOrderByProductIdAsc(tenantId, existing.getId());
            Map<UUID, Integer> original = new TreeMap<>();
            originalItems.forEach(item -> original.put(item.getProductId(), item.getQuantity()));
            if (!original.equals(requested)) {
                throw new ReservationConflictException("Request ID was already used with different reservation items");
            }
            log.debug("Reservation replay: tenantId={} warehouseId={} reservationId={}", tenantId, warehouseId, existing.getId());
            return new Result(ReservationResponse.from(existing, originalItems), false);
        }

        requested.forEach((product, quantity) -> locked.get(product).reserve(quantity));
        // Flush the parent first to enforce the unique request key before persisting child rows.
        var reservation = reservations.saveAndFlush(new InventoryReservation(tenantId, warehouseId, request.requestId()));
        var savedItems = items.saveAll(requested.entrySet().stream()
                .map(item -> new ReservationItem(tenantId, reservation.getId(), item.getKey(), item.getValue())).toList());
        events.publishEvent(new InventoryActivity("RESERVATION_CREATED", tenantId, warehouseId, reservation.getId()));
        return new Result(ReservationResponse.from(reservation, savedItems), true);
    }

    /** Releasing twice is safe: only the first ACTIVE -> RELEASED transition returns units. */
    @Transactional
    public ReservationResponse release(UUID tenantId, UUID warehouseId, UUID reservationId) {
        warehouses.getWarehouseById(tenantId, warehouseId);
        var reservation = reservations.findForUpdate(reservationId, tenantId, warehouseId)
                .orElseThrow(ReservationNotFoundException::new);
        var originalItems = items.findByTenantIdAndReservationIdOrderByProductIdAsc(tenantId, reservationId);
        if (!reservation.isActive()) {
            log.debug("Reservation release replay: tenantId={} reservationId={}", tenantId, reservationId);
            return ReservationResponse.from(reservation, originalItems);
        }
        Map<UUID, Integer> quantities = new TreeMap<>();
        originalItems.forEach(item -> quantities.put(item.getProductId(), item.getQuantity()));
        var locked = lockStocks(tenantId, warehouseId, quantities);
        quantities.forEach((product, quantity) -> locked.get(product).releaseReserved(quantity));
        reservation.release();
        events.publishEvent(new InventoryActivity("RESERVATION_RELEASED", tenantId, warehouseId, reservationId));
        return ReservationResponse.from(reservation, originalItems);
    }

    /** Missing records and records outside the trusted tenant/warehouse scope both return 404. */
    @Transactional(readOnly = true)
    public ReservationResponse getReservation(UUID tenantId, UUID warehouseId, UUID reservationId) {
        warehouses.getWarehouseById(tenantId, warehouseId);
        var reservation = reservations.findByIdAndTenantIdAndWarehouseId(reservationId, tenantId, warehouseId)
                .orElseThrow(ReservationNotFoundException::new);
        log.debug("Reservation lookup: tenantId={} reservationId={}", tenantId, reservationId);
        return ReservationResponse.from(reservation,
                items.findByTenantIdAndReservationIdOrderByProductIdAsc(tenantId, reservationId));
    }

    private Map<UUID, Stock> lockStocks(UUID tenantId, UUID warehouseId, Map<UUID, Integer> requested) {
        Map<UUID, Stock> locked = new TreeMap<>();
        // TreeMap imposes the same Java UUID order for create and release, avoiding opposite-order deadlocks.
        for (UUID productId : requested.keySet()) {
            locked.put(productId, stocks.findForUpdate(tenantId, warehouseId, productId)
                    .orElseThrow(StockNotFoundException::new));
        }
        return locked;
    }
}
