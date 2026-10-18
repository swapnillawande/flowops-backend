package com.flowops.product.service;

import com.flowops.product.dto.CreateProductRequest;
import com.flowops.product.entity.Product;
import com.flowops.product.exception.DuplicateProductException;
import com.flowops.product.exception.ProductNotFoundException;
import com.flowops.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProductService {
    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Product createProduct(UUID tenantId, CreateProductRequest request) {
        if (repository.existsByTenantIdAndSku(tenantId, request.sku())) {
            throw new DuplicateProductException(request.sku());
        }
        // The database unique constraint also protects concurrent submissions.
        return repository.save(new Product(tenantId, request.sku(), request.name(), request.weightKg()));
    }

    @Transactional(readOnly = true)
    public List<Product> getProducts(UUID tenantId) {
        return repository.findByTenantIdOrderBySkuAsc(tenantId);
    }

    @Transactional(readOnly = true)
    public Product getProduct(UUID tenantId, UUID id) {
        return repository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }
}
