package com.flowops.inventory.controller;

import com.flowops.inventory.dto.CreateStockRequest;
import com.flowops.inventory.dto.StockResponse;
import com.flowops.inventory.entity.Stock;
import com.flowops.inventory.service.StockService;
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
@RequestMapping("/api/v1/warehouses/{warehouseId}/stock")
public class StockController {
    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<StockResponse> createStock(@PathVariable UUID warehouseId,
                                                    @Valid @RequestBody CreateStockRequest request) {
        Stock saved = service.createStock(DemoTenant.ID, warehouseId, request);
        return ResponseEntity.created(URI.create("/api/v1/warehouses/" + warehouseId
                        + "/stock/" + saved.getProductId()))
                .body(StockResponse.from(saved));
    }

    @GetMapping
    public List<StockResponse> getStock(@PathVariable UUID warehouseId) {
        return service.getStock(DemoTenant.ID, warehouseId).stream().map(StockResponse::from).toList();
    }

    @GetMapping("/{productId}")
    public StockResponse getStock(@PathVariable UUID warehouseId, @PathVariable UUID productId) {
        return StockResponse.from(service.getStock(DemoTenant.ID, warehouseId, productId));
    }
}
