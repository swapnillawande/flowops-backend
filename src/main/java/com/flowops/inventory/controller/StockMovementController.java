package com.flowops.inventory.controller;

import com.flowops.inventory.dto.CreateStockMovementRequest;
import com.flowops.inventory.dto.StockMovementResponse;
import com.flowops.inventory.service.StockMovementService;
import com.flowops.tenant.DemoTenant;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/warehouses/{warehouseId}/stock/{productId}/movements")
public class StockMovementController {
    private final StockMovementService service;

    public StockMovementController(StockMovementService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<StockMovementResponse> create(@PathVariable UUID warehouseId,
                                                       @PathVariable UUID productId,
                                                       @Valid @RequestBody CreateStockMovementRequest request) {
        var result = service.createMovement(DemoTenant.ID, warehouseId, productId, request);
        return ResponseEntity.status(result.created() ? 201 : 200).body(StockMovementResponse.from(result.movement()));
    }

    @GetMapping
    public List<StockMovementResponse> list(@PathVariable UUID warehouseId, @PathVariable UUID productId) {
        return service.getMovements(DemoTenant.ID, warehouseId, productId).stream()
                .map(StockMovementResponse::from).toList();
    }
}
