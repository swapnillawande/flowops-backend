package com.flowops.shipment.dto;

import com.flowops.shipment.entity.ShipmentStatusEvent;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Historical transition snapshot; the current state is returned by the shipment endpoint. */
public record ShipmentStatusEventResponse(UUID id, long sequence, UUID requestId, String status, String note, OffsetDateTime createdAt, String actorSubject, String actorIssuer, String actorName) {
    public static ShipmentStatusEventResponse from(ShipmentStatusEvent event) {
        return new ShipmentStatusEventResponse(event.getId(), event.getSequence(), event.getRequestId(),
                event.getStatus(), event.getNote(), event.getCreatedAt(), event.getActorSubject(), event.getActorIssuer(), event.getActorName());
    }
}
