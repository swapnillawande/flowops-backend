package com.flowops.inventory.service;

import com.flowops.inventory.dto.CreateStockRequest;
import com.flowops.inventory.entity.Stock;
import com.flowops.inventory.entity.StockMovement;
import com.flowops.inventory.repository.StockMovementRepository;
import com.flowops.inventory.exception.DuplicateStockException;
import com.flowops.inventory.exception.StockNotFoundException;
import com.flowops.inventory.repository.StockRepository;
import com.flowops.product.service.ProductService;
import com.flowops.warehouse.service.WarehouseService;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import com.flowops.inventory.event.InventoryActivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Creates a balance and its opening audit entry atomically; verifies warehouse and product ownership. */
@Service
public class StockService {
    private static final Logger log = LoggerFactory.getLogger(StockService.class);
    private final ApplicationEventPublisher events;
    private final StockRepository repository;
    private final StockMovementRepository movements;
    private final WarehouseService warehouses;
    private final ProductService products;

    public StockService(StockRepository repository, WarehouseService warehouses, ProductService products,
                        StockMovementRepository movements, ApplicationEventPublisher events) {
        this.events = events;
        this.repository = repository;
        this.movements = movements;
        this.warehouses = warehouses;
        this.products = products;
    }

    @Transactional
    public Stock createStock(UUID tenantId, UUID warehouseId, CreateStockRequest request) {
        warehouses.getWarehouseById(tenantId, warehouseId);
        products.getProduct(tenantId, request.productId());
        if (repository.existsByTenantIdAndWarehouseIdAndProductId(tenantId, warehouseId, request.productId())) {
            throw new DuplicateStockException();
        }
        // The unique constraint protects simultaneous attempts after the precheck.
        Stock stock = repository.save(new Stock(tenantId, warehouseId, request.productId(), request.initialQuantity()));
        movements.save(new StockMovement(stock, null, "OPENING", stock.getOnHand(), 0, "Opening balance"));
        // The audit row and balance must commit together; success logging runs after that commit.
        events.publishEvent(new InventoryActivity("STOCK_OPENED", tenantId, warehouseId, stock.getId()));
        return stock;
    }

    @Transactional(readOnly = true)
    public List<Stock> getStock(UUID tenantId, UUID warehouseId) {
        warehouses.getWarehouseById(tenantId, warehouseId);
        log.debug("Stock list: tenantId={} warehouseId={}", tenantId, warehouseId);
        return repository.findByTenantIdAndWarehouseIdOrderByProductIdAsc(tenantId, warehouseId);
    }

    @Transactional(readOnly = true)
    public Stock getStock(UUID tenantId, UUID warehouseId, UUID productId) {
        warehouses.getWarehouseById(tenantId, warehouseId);
        return repository.findByTenantIdAndWarehouseIdAndProductId(tenantId, warehouseId, productId)
                .orElseThrow(StockNotFoundException::new);
    }
}
