package com.flowops.order.repository;

import com.flowops.order.entity.CustomerOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;
import java.util.UUID;

/** All reads include tenant scope. PostgreSQL's unique-key wait makes concurrent request claims safe. */
public interface OrderRepository extends JpaRepository<CustomerOrder, UUID> {
    /**
     * Exactly one transaction inserts a request key; a retry waits for its commit or rollback.
     * The PENDING row is never committed alone: stock failure rolls back this insert too.
     */
    @Modifying
    @Query(value = """
        INSERT INTO customer_orders (id, tenant_id, request_id, warehouse_id, customer_reference, status, created_at)
        VALUES (:id, :tenantId, :requestId, :warehouseId, :reference, 'PENDING', CURRENT_TIMESTAMP)
        ON CONFLICT (tenant_id, request_id) DO NOTHING
        """, nativeQuery = true)
    int claimRequest(UUID id, UUID tenantId, UUID requestId, UUID warehouseId, String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.tenantId = :tenantId and o.requestId = :requestId")
    Optional<CustomerOrder> findRequestForUpdate(UUID tenantId, UUID requestId);

    /** Cancellation serializes on the order first, then on its reservation and stock rows. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CustomerOrder o where o.id = :id and o.tenantId = :tenantId")
    Optional<CustomerOrder> findForUpdate(UUID id, UUID tenantId);

    Optional<CustomerOrder> findByIdAndTenantId(UUID id, UUID tenantId);
    Page<CustomerOrder> findByTenantId(UUID tenantId, Pageable pageable);
}
