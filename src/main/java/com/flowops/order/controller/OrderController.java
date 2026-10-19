package com.flowops.order.controller;

import com.flowops.order.dto.CreateOrderRequest;
import com.flowops.order.dto.OrderResponse;
import com.flowops.order.service.OrderService;
import com.flowops.tenant.DemoTenant;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/** HTTP boundary: validate commands, supply the trusted tenant, and expose only DTOs. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }

    /** New order returns 201; identical retries return the current order with 200, including after cancellation. */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        var result = service.create(DemoTenant.ID, request);
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/orders/" + result.response().id())).body(result.response())
                : ResponseEntity.ok(result.response());
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@PathVariable UUID orderId) { return service.get(DemoTenant.ID, orderId); }

    @GetMapping
    public OrderService.OrderPage list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.list(DemoTenant.ID, page, size);
    }

    /** Cancellation is a full, retry-safe release. Partial fulfillment is outside this milestone. */
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancel(@PathVariable UUID orderId) { return service.cancel(DemoTenant.ID, orderId); }
}
