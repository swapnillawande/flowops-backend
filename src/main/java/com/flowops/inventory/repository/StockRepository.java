package com.flowops.inventory.repository;

import com.flowops.inventory.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockRepository extends JpaRepository<Stock, UUID> {
    boolean existsByTenantIdAndWarehouseIdAndProductId(UUID tenantId, UUID warehouseId, UUID productId);
    Optional<Stock> findByTenantIdAndWarehouseIdAndProductId(UUID tenantId, UUID warehouseId, UUID productId);
    List<Stock> findByTenantIdAndWarehouseIdOrderByProductIdAsc(UUID tenantId, UUID warehouseId);
}
