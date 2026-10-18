package com.flowops.product.controller;

import com.flowops.product.dto.CreateProductRequest;
import com.flowops.product.dto.ProductResponse;
import com.flowops.product.entity.Product;
import com.flowops.product.service.ProductService;
import com.flowops.tenant.DemoTenant;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        Product saved = service.createProduct(DemoTenant.ID, request);
        return ResponseEntity.created(URI.create("/api/v1/products/" + saved.getId()))
                .body(ProductResponse.from(saved));
    }

    @GetMapping
    public List<ProductResponse> getProducts() {
        return service.getProducts(DemoTenant.ID).stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable UUID id) {
        return ProductResponse.from(service.getProduct(DemoTenant.ID, id));
    }
}
