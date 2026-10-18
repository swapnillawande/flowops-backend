package com.flowops.inventory.entity;

import com.flowops.inventory.exception.StockMovementConflictException;
import com.flowops.inventory.exception.ReservationConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** One product balance: available = onHand - reserved; reserving does not remove physical units. */
@Entity
@Table(name = "stock")
public class Stock {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Stock() {}

    public Stock(UUID tenantId, UUID warehouseId, UUID productId, int onHand) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.warehouseId = warehouseId;
        this.productId = productId;
        this.onHand = onHand;
        this.reserved = 0;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Caller holds the stock row lock; use long arithmetic to detect integer overflow before casting. */
    public void changeOnHand(int quantity) {
        long updated = (long) onHand + quantity;
        if (updated < reserved || updated > Integer.MAX_VALUE) {
            throw new StockMovementConflictException("Movement would exceed stock capacity or reduce stock below reserved quantity");
        }
        onHand = (int) updated;
    }

    /** Only free units may be held. The transaction keeps this check atomic with the increment. */
    public void reserve(int quantity) {
        if (quantity <= 0 || quantity > getAvailable()) {
            throw new ReservationConflictException("Insufficient available stock for reservation");
        }
        reserved += quantity;
    }

    /** Return a hold to availability without changing onHand. Invalid releases never silently clamp. */
    public void releaseReserved(int quantity) {
        if (quantity <= 0 || quantity > reserved) {
            throw new ReservationConflictException("Reservation quantity exceeds currently reserved stock");
        }
        reserved -= quantity;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getProductId() { return productId; }
    public int getOnHand() { return onHand; }
    public int getReserved() { return reserved; }
    public int getAvailable() { return onHand - reserved; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
