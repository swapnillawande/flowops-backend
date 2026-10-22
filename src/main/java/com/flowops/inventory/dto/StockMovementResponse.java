package com.flowops.inventory.dto;

import com.flowops.inventory.entity.StockMovement;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Historical before/after balance snapshot; replaying a movement returns this original snapshot. */
public record StockMovementResponse(UUID id, UUID stockId, UUID requestId, UUID shipmentId, String type, int quantity,
                                    int onHandBefore, int onHandAfter, String reason, OffsetDateTime createdAt, String actorSubject, String actorIssuer, String actorName) {
    public static StockMovementResponse from(StockMovement movement) {
        return new StockMovementResponse(movement.getId(), movement.getStockId(), movement.getRequestId(), movement.getShipmentId(),
                movement.getType(), movement.getQuantity(), movement.getOnHandBefore(),
                movement.getOnHandAfter(), movement.getReason(), movement.getCreatedAt(), movement.getActorSubject(), movement.getActorIssuer(), movement.getActorName());
    }
}
