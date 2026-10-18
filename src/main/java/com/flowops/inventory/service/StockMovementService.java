package com.flowops.inventory.service;

import com.flowops.inventory.dto.CreateStockMovementRequest;
import com.flowops.inventory.entity.Stock;
import com.flowops.inventory.entity.StockMovement;
import com.flowops.inventory.exception.StockMovementConflictException;
import com.flowops.inventory.exception.StockNotFoundException;
import com.flowops.inventory.repository.StockMovementRepository;
import com.flowops.inventory.repository.StockRepository;
import com.flowops.warehouse.service.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import com.flowops.inventory.event.InventoryActivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Applies physical stock changes and their audit entries atomically; retry checks share the stock lock. */
@Service
public class StockMovementService {
    private static final Logger log = LoggerFactory.getLogger(StockMovementService.class);
    private final ApplicationEventPublisher events;
    private final StockRepository stocks;
    private final StockMovementRepository movements;
    private final WarehouseService warehouses;

    public StockMovementService(StockRepository stocks, StockMovementRepository movements,
                                WarehouseService warehouses, ApplicationEventPublisher events) {
        this.events = events;
        this.stocks = stocks;
        this.movements = movements;
        this.warehouses = warehouses;
    }

    public record Result(StockMovement movement, boolean created) {}

    @Transactional
    public Result createMovement(UUID tenantId, UUID warehouseId, UUID productId,
                                 CreateStockMovementRequest request) {
        if (request.quantity() == 0 || (request.type() == CreateStockMovementRequest.Type.RECEIPT
                && request.quantity() < 0)) {
            throw new IllegalArgumentException("Receipt quantity must be positive; adjustment quantity must be nonzero");
        }
        warehouses.getWarehouseById(tenantId, warehouseId);
        // Serialize changes to one balance, including idempotency checks, within this transaction.
        Stock stock = stocks.findForUpdate(tenantId, warehouseId, productId)
                .orElseThrow(StockNotFoundException::new);
        var previous = movements.findByTenantIdAndStockIdAndRequestId(tenantId, stock.getId(), request.requestId());
        if (previous.isPresent()) {
            StockMovement existing = previous.get();
            if (!existing.getType().equals(request.type().name()) || existing.getQuantity() != request.quantity()
                    || !existing.getReason().equals(request.reason())) {
                throw new StockMovementConflictException("Request ID was already used with a different movement");
            }
            log.debug("Stock movement replay: tenantId={} movementId={}", tenantId, existing.getId());
            return new Result(existing, false);
        }
        int before = stock.getOnHand();
        // Stock is managed by JPA: dirty checking persists this change at commit; no separate save is needed.
        stock.changeOnHand(request.quantity());
        StockMovement movement = movements.save(new StockMovement(stock, request.requestId(),
                request.type().name(), request.quantity(), before, request.reason()));
        events.publishEvent(new InventoryActivity("STOCK_MOVEMENT_CREATED", tenantId, warehouseId, movement.getId()));
        return new Result(movement, true);
    }

    @Transactional(readOnly = true)
    public List<StockMovement> getMovements(UUID tenantId, UUID warehouseId, UUID productId) {
        warehouses.getWarehouseById(tenantId, warehouseId);
        Stock stock = stocks.findByTenantIdAndWarehouseIdAndProductId(tenantId, warehouseId, productId)
                .orElseThrow(StockNotFoundException::new);
        return movements.findByTenantIdAndStockIdOrderByCreatedAtAscIdAsc(tenantId, stock.getId());
    }
}
