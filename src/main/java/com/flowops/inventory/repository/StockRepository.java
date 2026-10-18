package com.flowops.inventory.repository;

import com.flowops.inventory.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Stock access is tenant-scoped. Mutating workflows must acquire findForUpdate before checking balances. */
public interface StockRepository extends JpaRepository<Stock, UUID> {
    /** PostgreSQL holds this row lock until commit/rollback, serializing all quantity changes for the balance. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.tenantId = :tenantId and s.warehouseId = :warehouseId and s.productId = :productId")
    Optional<Stock> findForUpdate(UUID tenantId, UUID warehouseId, UUID productId);

    boolean existsByTenantIdAndWarehouseIdAndProductId(UUID tenantId, UUID warehouseId, UUID productId);
    Optional<Stock> findByTenantIdAndWarehouseIdAndProductId(UUID tenantId, UUID warehouseId, UUID productId);
    List<Stock> findByTenantIdAndWarehouseIdOrderByProductIdAsc(UUID tenantId, UUID warehouseId);
}
