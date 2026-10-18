package com.flowops.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "products")
public class Product {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "weight_kg", nullable = false, precision = 9, scale = 3)
    private BigDecimal weightKg;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Product() {}

    public Product(UUID tenantId, String sku, String name, BigDecimal weightKg) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.sku = sku;
        this.name = name;
        this.weightKg = weightKg;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public BigDecimal getWeightKg() { return weightKg; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
