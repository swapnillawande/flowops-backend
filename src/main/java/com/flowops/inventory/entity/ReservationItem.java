package com.flowops.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** Immutable quantities retained after release so retries and history remain explainable. */
@Entity
@Table(name = "inventory_reservation_items")
public class ReservationItem {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;
    @Column(name = "product_id", nullable = false)
    private UUID productId;
    @Column(nullable = false)
    private int quantity;

    protected ReservationItem() {}

    public ReservationItem(UUID tenantId, UUID reservationId, UUID productId, int quantity) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.reservationId = reservationId;
        this.productId = productId;
        this.quantity = quantity;
    }

    public UUID getProductId() { return productId; }
    public int getQuantity() { return quantity; }
}
