
package com.flowops.warehouse.repository;

import com.flowops.warehouse.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped warehouse queries; use these lookups for business operations instead of findById. */
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    List<Warehouse> findByTenantId(UUID tenantId);

    Optional<Warehouse> findByIdAndTenantId(
            UUID id,
            UUID tenantId
    );

    boolean existsByTenantIdAndNameIgnoreCase(
            UUID tenantId,
            String name
    );
}
