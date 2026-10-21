package com.flowops.shipment.controller;

import com.flowops.shipment.dto.CreateShipmentRequest;
import com.flowops.shipment.dto.UpdateShipmentStatusRequest;
import com.flowops.shipment.dto.ShipmentStatusEventResponse;
import java.util.List;
import com.flowops.shipment.dto.ShipmentResponse;
import com.flowops.shipment.service.ShipmentService;
import com.flowops.tenant.DemoTenant;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

/** Shipping HTTP boundary; full-order quantities come from the order, never from a dispatch request body. */
@RestController
@RequestMapping("/api/v1/shipments")
public class ShipmentController {
    private final ShipmentService service;
    public ShipmentController(ShipmentService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ShipmentResponse> create(@Valid @RequestBody CreateShipmentRequest request) {
        var result = service.create(DemoTenant.ID, request);
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/shipments/" + result.response().id())).body(result.response())
                : ResponseEntity.ok(result.response());
    }

    @GetMapping("/{shipmentId}")
    public ShipmentResponse get(@PathVariable UUID shipmentId) { return service.get(DemoTenant.ID, shipmentId); }

    @GetMapping
    public ShipmentService.ShipmentPage list(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return service.list(DemoTenant.ID, page, size);
    }

    @GetMapping("/{shipmentId}/history")
    public List<ShipmentStatusEventResponse> history(@PathVariable UUID shipmentId) {
        return service.history(DemoTenant.ID, shipmentId);
    }

    /** Operators record transit/delivery updates; this is not an external carrier webhook. */
    @PostMapping("/{shipmentId}/status")
    public ShipmentStatusEventResponse update(@PathVariable UUID shipmentId, @Valid @RequestBody UpdateShipmentStatusRequest request) {
        return service.updateStatus(DemoTenant.ID, shipmentId, request);
    }

    /** Retrying a dispatch returns the existing result without deducting stock again. */
    @PostMapping("/{shipmentId}/dispatch")
    public ShipmentResponse dispatch(@PathVariable UUID shipmentId) { return service.dispatch(DemoTenant.ID, shipmentId); }
}
