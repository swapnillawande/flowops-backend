package com.flowops.shipment.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Append-only through the API; sequence preserves transition order even when timestamps tie. */
@Entity
@Table(name = "shipment_status_events")
public class ShipmentStatusEvent {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "shipment_id", nullable = false) private UUID shipmentId;
    @Column(nullable = false) private long sequence;
    @Column(name = "request_id") private UUID requestId;
    @Column(nullable = false, length = 20) private String status;
    @Column(nullable = false, length = 250) private String note;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    protected ShipmentStatusEvent() {}
    public ShipmentStatusEvent(UUID tenantId, Shipment shipment, long sequence, UUID requestId, String note) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.shipmentId = shipment.getId();
        this.sequence = sequence; this.requestId = requestId; this.status = shipment.getStatus(); this.note = note;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
    public UUID getId() { return id; }
    public long getSequence() { return sequence; }
    public UUID getRequestId() { return requestId; }
    public String getStatus() { return status; }
    public String getNote() { return note; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
