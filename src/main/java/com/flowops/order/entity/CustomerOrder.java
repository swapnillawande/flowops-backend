package com.flowops.order.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Order lifecycle; PENDING exists only inside creation's transaction until stock has been held. */
@Entity
@Table(name = "customer_orders")
public class CustomerOrder {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "request_id", nullable = false) private UUID requestId;
    @Column(name = "warehouse_id", nullable = false) private UUID warehouseId;
    @Column(name = "customer_reference", nullable = false, length = 100) private String customerReference;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "reservation_id") private UUID reservationId;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "dispatched_at") private OffsetDateTime dispatchedAt;
    @Column(name = "cancelled_at") private OffsetDateTime cancelledAt;

    protected CustomerOrder() {} // Creation uses the repository's atomic request-key claim.

    /** Only creation may attach the newly created hold; it is committed with the order. */
    public void confirm(UUID reservationId) {
        if (!"PENDING".equals(status)) throw new IllegalStateException("Only pending orders can be confirmed");
        this.reservationId = reservationId;
        status = "CONFIRMED";
    }

    /** The service releases the hold in this same transaction before applying the terminal state. */
    public void cancel() {
        if (!"CONFIRMED".equals(status)) throw new IllegalStateException("Only confirmed orders can be cancelled");
        status = "CANCELLED";
        cancelledAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Called after inventory consumes the hold, within the same shipment transaction. */
    public void dispatch() {
        if (!"CONFIRMED".equals(status)) throw new IllegalStateException("Only confirmed orders can be dispatched");
        status = "DISPATCHED";
        dispatchedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public OffsetDateTime getDispatchedAt() { return dispatchedAt; }
    public UUID getId() { return id; }
    public UUID getRequestId() { return requestId; }
    public UUID getWarehouseId() { return warehouseId; }
    public String getCustomerReference() { return customerReference; }
    public String getStatus() { return status; }
    public UUID getReservationId() { return reservationId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getCancelledAt() { return cancelledAt; }
}
