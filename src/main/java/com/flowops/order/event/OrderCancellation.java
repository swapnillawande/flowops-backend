package com.flowops.order.event;

import java.util.UUID;

/** Synchronous in-transaction notification: shipment cancellation must fail/commit with order cancellation. */
public record OrderCancellation(UUID tenantId, UUID orderId) {}
