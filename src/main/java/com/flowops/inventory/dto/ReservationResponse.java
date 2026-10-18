package com.flowops.inventory.dto;

import com.flowops.inventory.entity.InventoryReservation;
import com.flowops.inventory.entity.ReservationItem;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Comparator;
import java.util.UUID;

/** API snapshot of the hold and its original items, including the release timestamp when terminal. */
public record ReservationResponse(UUID id, UUID warehouseId, UUID requestId, String status,
                                  List<CreateReservationRequest.Item> items,
                                  OffsetDateTime createdAt, OffsetDateTime releasedAt) {
    public static ReservationResponse from(InventoryReservation reservation, List<ReservationItem> items) {
        return new ReservationResponse(reservation.getId(), reservation.getWarehouseId(), reservation.getRequestId(),
                reservation.getStatus(), items.stream().sorted(Comparator.comparing(ReservationItem::getProductId))
                .map(item -> new CreateReservationRequest.Item(item.getProductId(), item.getQuantity())).toList(),
                reservation.getCreatedAt(), reservation.getReleasedAt());
    }
}
