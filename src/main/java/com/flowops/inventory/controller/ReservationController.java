package com.flowops.inventory.controller;

import com.flowops.inventory.dto.CreateReservationRequest;
import com.flowops.inventory.dto.ReservationResponse;
import com.flowops.inventory.service.ReservationService;
import com.flowops.security.CurrentTenant;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** Validates HTTP input and supplies the server-owned tenant; transactions live in the service. */
@RestController
@RequestMapping("/api/v1/warehouses/{warehouseId}/reservations")
public class ReservationController {
    private final CurrentTenant currentTenant;
    private final ReservationService service;

    public ReservationController(ReservationService service, CurrentTenant currentTenant) {
        this.currentTenant = currentTenant;
        this.service = service;
    }

    /** New holds return 201 with Location; an identical request replay returns the current hold with 200. */
    @PostMapping
    public ResponseEntity<ReservationResponse> create(@PathVariable UUID warehouseId,
                                                      @Valid @RequestBody CreateReservationRequest request) {
        var result = service.reserve(currentTenant.id(), warehouseId, request);
        if (!result.created()) {
            return ResponseEntity.ok(result.response());
        }
        return ResponseEntity.created(URI.create("/api/v1/warehouses/" + warehouseId
                + "/reservations/" + result.response().id())).body(result.response());
    }

    @GetMapping("/{reservationId}")
    public ReservationResponse get(@PathVariable UUID warehouseId, @PathVariable UUID reservationId) {
        return service.getReservation(currentTenant.id(), warehouseId, reservationId);
    }

    /** No quantity is accepted here: release always returns exactly the original held units. */
    @PostMapping("/{reservationId}/release")
    public ReservationResponse release(@PathVariable UUID warehouseId, @PathVariable UUID reservationId) {
        return service.release(currentTenant.id(), warehouseId, reservationId);
    }
}
