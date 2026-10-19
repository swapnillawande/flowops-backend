package com.flowops.shipment.repository;

import com.flowops.shipment.entity.Shipment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped access. Mutations lock the order first, then the shipment, to avoid inconsistent lock order. */
public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {
    Optional<Shipment> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<Shipment> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);
    Page<Shipment> findByTenantId(UUID tenantId, Pageable pageable);

    /** A scalar lookup avoids loading a stale entity into JPA before acquiring the order lock. */
    @Query("select s.orderId from Shipment s where s.id = :id and s.tenantId = :tenantId")
    Optional<UUID> findOrderId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.id = :id and s.tenantId = :tenantId")
    Optional<Shipment> findForUpdate(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Shipment s where s.orderId = :orderId and s.tenantId = :tenantId")
    Optional<Shipment> findOrderForUpdate(UUID orderId, UUID tenantId);
}
