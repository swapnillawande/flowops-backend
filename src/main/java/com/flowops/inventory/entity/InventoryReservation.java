package com.flowops.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Durable lifecycle of a warehouse hold; release is a terminal, retry-safe transition. */
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Column(name = "request_id", nullable = false)
    private UUID requestId;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    protected InventoryReservation() {} // JPA constructs entities when loading database rows.

    public InventoryReservation(UUID tenantId, UUID warehouseId, UUID requestId) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.warehouseId = warehouseId;
        this.requestId = requestId;
        this.status = "ACTIVE";
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Called only while holding the reservation lock, after all held units are returned. */
    public void release() {
        if (isActive()) {
            status = "RELEASED";
            releasedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public boolean isActive() { return "ACTIVE".equals(status); }
    public UUID getId() { return id; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getRequestId() { return requestId; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
}
