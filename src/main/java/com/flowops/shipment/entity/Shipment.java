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
    @Column(name = "in_transit_at") private OffsetDateTime inTransitAt;
    @Column(name = "delivered_at") private OffsetDateTime deliveredAt;
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
    /** No skipping or regressing states: physical dispatch must already have happened. */
    public void advance(String next) {
        if ("IN_TRANSIT".equals(next) && "DISPATCHED".equals(status)) {
            status = next; inTransitAt = OffsetDateTime.now(ZoneOffset.UTC);
        } else if ("DELIVERED".equals(next) && "IN_TRANSIT".equals(status)) {
            status = next; deliveredAt = OffsetDateTime.now(ZoneOffset.UTC);
        } else throw new ShipmentConflictException("Shipment status must progress from dispatched to in transit to delivered");
    }
    public OffsetDateTime getInTransitAt() { return inTransitAt; }
    public OffsetDateTime getDeliveredAt() { return deliveredAt; }
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
