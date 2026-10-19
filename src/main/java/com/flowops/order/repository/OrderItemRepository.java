package com.flowops.order.repository;

import com.flowops.order.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/** Item snapshots are immutable and always retrieved through their tenant-owned order. */
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {
    List<OrderItem> findByTenantIdAndOrderId(UUID tenantId, UUID orderId);
}
