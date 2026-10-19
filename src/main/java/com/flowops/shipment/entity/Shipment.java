package com.flowops.shipment.entity;

import jakarta.persistence.*;
import com.flowops.shipment.exception.ShipmentConflictException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** One shipment per order; physical fulfillment happens only at the PREPARED -> DISPATCHED transition. */
@Entity
@Table(name = "shipments")
public class Shipment {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "warehouse_id", nullable = false) private UUID warehouseId;
    @Column(name = "tracking_number", nullable = false, length = 40) private String trackingNumber;
    @Column(nullable = false, length = 80) private String carrier;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "dispatched_at") private OffsetDateTime dispatchedAt;
    @Column(name = "cancelled_at") private OffsetDateTime cancelledAt;

    protected Shipment() {}
    public Shipment(UUID tenantId, UUID orderId, UUID warehouseId, String carrier) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.warehouseId = warehouseId;
        this.trackingNumber = "FLO-" + id.toString().toUpperCase(java.util.Locale.ROOT);
        this.carrier = carrier;
        this.status = "PREPARED";
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
    /** Caller has verified lifecycle rules and locked both order and shipment. */
    public void dispatch() {
        if (!"PREPARED".equals(status)) throw new ShipmentConflictException("Only prepared shipments can be dispatched");
        status = "DISPATCHED";
        dispatchedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
    /** Only the order cancellation workflow invokes this transition; dispatch is terminal for cancellation. */
    public void cancel() {
        if (!"PREPARED".equals(status)) throw new ShipmentConflictException("Only prepared shipments can be cancelled");
        status = "CANCELLED";
        cancelledAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getWarehouseId() { return warehouseId; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getCarrier() { return carrier; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDispatchedAt() { return dispatchedAt; }
    public OffsetDateTime getCancelledAt() { return cancelledAt; }
}
