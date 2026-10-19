package com.flowops.order.entity;

import jakarta.persistence.*;
import java.util.UUID;

/** Immutable catalog snapshot: later product renames must not rewrite order history. */
@Entity
@Table(name = "customer_order_items")
public class OrderItem {
    @Id private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "order_id", nullable = false) private UUID orderId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(nullable = false, length = 64) private String sku;
    @Column(name = "product_name", nullable = false, length = 150) private String productName;
    @Column(nullable = false) private int quantity;

    protected OrderItem() {}

    public OrderItem(UUID tenantId, UUID orderId, UUID productId, String sku, String productName, int quantity) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.productId = productId;
        this.sku = sku;
        this.productName = productName;
        this.quantity = quantity;
    }
    public UUID getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
}
