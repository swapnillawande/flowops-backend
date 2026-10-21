package com.flowops.shipment.repository;

import com.flowops.shipment.entity.ShipmentStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.*;

/** Read retry keys and history within the trusted tenant and shipment scope. */
public interface ShipmentStatusEventRepository extends JpaRepository<ShipmentStatusEvent, UUID> {
    Optional<ShipmentStatusEvent> findByTenantIdAndShipmentIdAndRequestId(UUID tenantId, UUID shipmentId, UUID requestId);
    List<ShipmentStatusEvent> findByTenantIdAndShipmentIdOrderBySequenceAsc(UUID tenantId, UUID shipmentId);
    @Query("select coalesce(max(e.sequence), 0) from ShipmentStatusEvent e where e.tenantId = :tenantId and e.shipmentId = :shipmentId")
    long lastSequence(UUID tenantId, UUID shipmentId);
}
