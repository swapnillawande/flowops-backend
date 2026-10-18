
package com.flowops.warehouse.controller;

import com.flowops.warehouse.dto.CreateWarehouseRequest;
import com.flowops.warehouse.dto.WarehouseResponse;
import com.flowops.warehouse.entity.Warehouse;
import com.flowops.warehouse.service.WarehouseService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {

    private static final UUID DEMO_TENANT_ID =
            UUID.fromString(
                "11111111-1111-4111-8111-111111111111"
            );

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @PostMapping
    public ResponseEntity<WarehouseResponse> createWarehouse(@Valid @RequestBody CreateWarehouseRequest request) {

        Warehouse saved = warehouseService.createWarehouse(
                DEMO_TENANT_ID,
                request.name(),
                request.city(),
                request.countryCode()
        );

        WarehouseResponse response = WarehouseResponse.from(saved);

        return ResponseEntity
                .created(URI.create(
                        "/api/v1/warehouses/" + saved.getId()
                ))
                .body(response);
    }

    @GetMapping
    public List<WarehouseResponse> getWarehouses() {

        return warehouseService.getWarehouses(DEMO_TENANT_ID)
                .stream()
                .map(WarehouseResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public WarehouseResponse getWarehouseById(
            @PathVariable UUID id) {

        Warehouse warehouse = warehouseService
                .getWarehouseById(DEMO_TENANT_ID, id);

        return WarehouseResponse.from(warehouse);
    }
}
