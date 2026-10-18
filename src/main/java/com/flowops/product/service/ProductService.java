package com.flowops.product.service;

import com.flowops.product.dto.CreateProductRequest;
import com.flowops.product.entity.Product;
import com.flowops.product.exception.DuplicateProductException;
import com.flowops.product.exception.ProductNotFoundException;
import com.flowops.product.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Catalog operations; the transaction commits product changes before returning to the HTTP controller. */
@Service
public class ProductService {
    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
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
        Product product = new Product(tenantId, request.sku(), request.name(), request.weightKg());
        log.debug("Product creation prepared: tenantId={} productId={}", tenantId, product.getId());
        return repository.save(product);
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
