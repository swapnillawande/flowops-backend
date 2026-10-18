package com.flowops.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Append-only through the API: immutable evidence of a physical quantity change in the same transaction. */
@Entity
@Table(name = "stock_movements")
public class StockMovement {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "stock_id", nullable = false)
    private UUID stockId;
    @Column(name = "request_id")
    private UUID requestId;
    @Column(nullable = false, length = 20)
    private String type;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "on_hand_before", nullable = false)
    private int onHandBefore;
    @Column(name = "on_hand_after", nullable = false)
    private int onHandAfter;
    @Column(nullable = false, length = 250)
    private String reason;
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected StockMovement() {}

    public StockMovement(Stock stock, UUID requestId, String type, int quantity,
                         int onHandBefore, String reason) {
        this.id = UUID.randomUUID();
        this.tenantId = stock.getTenantId();
        this.stockId = stock.getId();
        this.requestId = requestId;
        this.type = type;
        this.quantity = quantity;
        this.onHandBefore = onHandBefore;
        this.onHandAfter = stock.getOnHand();
        this.reason = reason;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() { return id; }
    public UUID getStockId() { return stockId; }
    public UUID getRequestId() { return requestId; }
    public String getType() { return type; }
    public int getQuantity() { return quantity; }
    public int getOnHandBefore() { return onHandBefore; }
    public int getOnHandAfter() { return onHandAfter; }
    public String getReason() { return reason; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
