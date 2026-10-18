package com.flowops.product.dto;

import com.flowops.product.entity.Product;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ProductResponse(UUID id, String sku, String name, BigDecimal weightKg,
                              OffsetDateTime createdAt) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getSku(), product.getName(),
                product.getWeightKg(), product.getCreatedAt());
    }
}
