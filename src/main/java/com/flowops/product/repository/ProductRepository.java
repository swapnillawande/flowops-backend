package com.flowops.product.repository;

import com.flowops.product.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsByTenantIdAndSku(UUID tenantId, String sku);
    List<Product> findByTenantIdOrderBySkuAsc(UUID tenantId);
    Optional<Product> findByIdAndTenantId(UUID id, UUID tenantId);
}
