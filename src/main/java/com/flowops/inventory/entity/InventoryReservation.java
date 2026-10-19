package com.flowops.inventory.entity;

import com.flowops.inventory.exception.ReservationConflictException;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Durable warehouse hold: release returns held units, while consumption removes the physical units. */
@Entity
@Table(name = "inventory_reservations")
public class InventoryReservation {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;
    @Column(name = "owner_order_id")
    private UUID ownerOrderId;
    @Column(name = "request_id", nullable = false)
    private UUID requestId;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
    @Column(name = "consumed_at")
    private OffsetDateTime consumedAt;
    @Column(name = "consumed_shipment_id")
    private UUID consumedShipmentId;
    @Column(name = "released_at")
    private OffsetDateTime releasedAt;

    protected InventoryReservation() {} // JPA constructs entities when loading database rows.

    public InventoryReservation(UUID tenantId, UUID warehouseId, UUID requestId) {
        this(tenantId, warehouseId, requestId, null);
    }

    /** An order-owned hold can be released only through the order workflow. */
    public InventoryReservation(UUID tenantId, UUID warehouseId, UUID requestId, UUID ownerOrderId) {
        this.ownerOrderId = ownerOrderId;
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

    /** Consumed holds cannot later be released; physical units have already left the warehouse. */
    public void consume(UUID shipmentId) {
        if (!isActive()) throw new ReservationConflictException("Only active holds can be consumed");
        Objects.requireNonNull(shipmentId, "Shipment ID is required");
        status = "CONSUMED";
        consumedAt = OffsetDateTime.now(ZoneOffset.UTC);
        consumedShipmentId = shipmentId;
    }
    public OffsetDateTime getConsumedAt() { return consumedAt; }
    public UUID getConsumedShipmentId() { return consumedShipmentId; }
    public boolean isActive() { return "ACTIVE".equals(status); }
    public UUID getOwnerOrderId() { return ownerOrderId; }
    public UUID getId() { return id; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getRequestId() { return requestId; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getReleasedAt() { return releasedAt; }
}
