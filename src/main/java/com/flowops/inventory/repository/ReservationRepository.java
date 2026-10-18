package com.flowops.inventory.repository;

import com.flowops.inventory.entity.InventoryReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

/** Every operational lookup includes the trusted tenant and warehouse scope. */
public interface ReservationRepository extends JpaRepository<InventoryReservation, UUID> {
    Optional<InventoryReservation> findByTenantIdAndWarehouseIdAndRequestId(UUID tenantId, UUID warehouseId, UUID requestId);
    Optional<InventoryReservation> findByIdAndTenantIdAndWarehouseId(UUID id, UUID tenantId, UUID warehouseId);

    /** Serialize release attempts before touching stock, so a retry never returns held units twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InventoryReservation r where r.id = :id and r.tenantId = :tenantId and r.warehouseId = :warehouseId")
    Optional<InventoryReservation> findForUpdate(UUID id, UUID tenantId, UUID warehouseId);
}
