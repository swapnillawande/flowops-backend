package com.flowops.order.event;

import java.util.UUID;

/** Identifiers only; customer references and catalog names are intentionally excluded from logs. */
public record OrderActivity(String operation, UUID tenantId, UUID orderId) {}
