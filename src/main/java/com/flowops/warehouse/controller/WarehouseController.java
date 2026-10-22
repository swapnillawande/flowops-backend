
package com.flowops.warehouse.controller;

import com.flowops.security.CurrentTenant;
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

/** Warehouse HTTP boundary. The tenant comes from a verified identity, never from request data. */
@RestController
@RequestMapping("/api/v1/warehouses")
public class WarehouseController {
    private final CurrentTenant currentTenant;

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService, CurrentTenant currentTenant) {
        this.currentTenant = currentTenant;
        this.warehouseService = warehouseService;
    }

    @PostMapping
    public ResponseEntity<WarehouseResponse> createWarehouse(@Valid @RequestBody CreateWarehouseRequest request) {

        Warehouse saved = warehouseService.createWarehouse(
                currentTenant.id(),
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

        return warehouseService.getWarehouses(currentTenant.id())
                .stream()
                .map(WarehouseResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public WarehouseResponse getWarehouseById(
            @PathVariable UUID id) {

        Warehouse warehouse = warehouseService
                .getWarehouseById(currentTenant.id(), id);

        return WarehouseResponse.from(warehouse);
    }
}
