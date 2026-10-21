package com.flowops.shipment.dto;

import com.flowops.shipment.entity.Shipment;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Tracking number is generated internally; live carrier tracking is not implemented yet. */
public record ShipmentResponse(UUID id, UUID orderId, UUID warehouseId, String trackingNumber, String carrier,
                               String status, OffsetDateTime createdAt, OffsetDateTime dispatchedAt, OffsetDateTime cancelledAt, OffsetDateTime inTransitAt, OffsetDateTime deliveredAt) {
    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(shipment.getId(), shipment.getOrderId(), shipment.getWarehouseId(),
                shipment.getTrackingNumber(), shipment.getCarrier(), shipment.getStatus(), shipment.getCreatedAt(),
                shipment.getDispatchedAt(), shipment.getCancelledAt(), shipment.getInTransitAt(), shipment.getDeliveredAt());
    }
}
