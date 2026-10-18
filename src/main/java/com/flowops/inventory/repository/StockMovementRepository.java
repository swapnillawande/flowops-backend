package com.flowops.inventory.repository;

import com.flowops.inventory.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {
    Optional<StockMovement> findByTenantIdAndStockIdAndRequestId(UUID tenantId, UUID stockId, UUID requestId);
    List<StockMovement> findByTenantIdAndStockIdOrderByCreatedAtAscIdAsc(UUID tenantId, UUID stockId);
}
