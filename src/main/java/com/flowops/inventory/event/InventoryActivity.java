package com.flowops.inventory.event;

import java.util.UUID;

/** Identifiers only: reasons, request bodies, and customer details do not belong in operational logs. */
public record InventoryActivity(String operation, UUID tenantId, UUID warehouseId, UUID recordId) {}
