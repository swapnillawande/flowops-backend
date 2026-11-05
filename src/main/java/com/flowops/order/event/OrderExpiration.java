package com.flowops.order.event;

import java.util.UUID;
import java.time.OffsetDateTime;

/** Synchronous notification: prepared shipment cancellation joins the order-expiry transaction. */
public record OrderExpiration(UUID tenantId, UUID orderId, OffsetDateTime occurredAt) {}
