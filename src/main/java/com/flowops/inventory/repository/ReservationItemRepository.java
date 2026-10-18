package com.flowops.inventory.repository;

import com.flowops.inventory.entity.ReservationItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Items are read in stable order for responses; stock lock order is chosen by the service. */
public interface ReservationItemRepository extends JpaRepository<ReservationItem, UUID> {
    List<ReservationItem> findByTenantIdAndReservationIdOrderByProductIdAsc(UUID tenantId, UUID reservationId);
}
