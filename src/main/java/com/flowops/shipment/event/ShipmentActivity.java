package com.flowops.shipment.event;

import java.util.UUID;

/** Operational identifiers only: carrier labels and request bodies are excluded. */
public record ShipmentActivity(String operation, UUID tenantId, UUID shipmentId, UUID orderId) {}
