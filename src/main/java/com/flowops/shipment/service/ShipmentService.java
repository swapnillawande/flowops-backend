package com.flowops.shipment.service;

import com.flowops.order.event.OrderCancellation;
import com.flowops.order.service.OrderService;
import com.flowops.shipment.dto.CreateShipmentRequest;
import com.flowops.shipment.dto.ShipmentResponse;
import com.flowops.shipment.entity.Shipment;
import com.flowops.shipment.event.ShipmentActivity;
import com.flowops.shipment.exception.ShipmentConflictException;
import com.flowops.shipment.exception.ShipmentNotFoundException;
import com.flowops.shipment.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Coordinates full-order shipping; every mutation locks order -> shipment -> reservation -> stock. */
@Service
public class ShipmentService {
    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);
    private final ShipmentRepository shipments;
    private final OrderService orders;
    private final ApplicationEventPublisher events;

    public ShipmentService(ShipmentRepository shipments, OrderService orders, ApplicationEventPublisher events) {
        this.shipments = shipments;
        this.orders = orders;
        this.events = events;
    }
    public record Result(ShipmentResponse response, boolean created) {}
    public record ShipmentPage(List<ShipmentResponse> content, int page, int size, long totalElements, int totalPages) {}

    /** One shipment per order. The same carrier replays the existing shipment in any terminal state. */
    @Transactional
    public Result create(UUID tenantId, CreateShipmentRequest request) {
        var order = orders.lockForFulfillment(tenantId, request.orderId());
        var existing = shipments.findByTenantIdAndOrderId(tenantId, request.orderId());
        if (existing.isPresent()) {
            var shipment = existing.get();
            if (!shipment.getCarrier().equals(request.carrier())) {
                throw new ShipmentConflictException("Order already has a shipment with a different carrier");
            }
            log.debug("Shipment creation replay: tenantId={} shipmentId={}", tenantId, shipment.getId());
            return new Result(ShipmentResponse.from(shipment), false);
        }
        if (!"CONFIRMED".equals(order.status())) {
            throw new ShipmentConflictException("Only confirmed orders can have a new shipment");
        }
        var shipment = shipments.save(new Shipment(tenantId, order.id(), order.warehouseId(), request.carrier()));
        events.publishEvent(new ShipmentActivity("SHIPMENT_PREPARED", tenantId, shipment.getId(), order.id()));
        return new Result(ShipmentResponse.from(shipment), true);
    }

    /** The order and inventory services join this transaction; no carrier/network call happens under the locks. */
    @Transactional
    public ShipmentResponse dispatch(UUID tenantId, UUID shipmentId) {
        UUID orderId = shipments.findOrderId(shipmentId, tenantId).orElseThrow(ShipmentNotFoundException::new);
        orders.lockForFulfillment(tenantId, orderId);
        var shipment = shipments.findForUpdate(shipmentId, tenantId).orElseThrow(ShipmentNotFoundException::new);
        if ("DISPATCHED".equals(shipment.getStatus())) {
            log.debug("Shipment dispatch replay: tenantId={} shipmentId={}", tenantId, shipmentId);
            return ShipmentResponse.from(shipment);
        }
        if (!"PREPARED".equals(shipment.getStatus())) {
            throw new ShipmentConflictException("Only prepared shipments can be dispatched");
        }
        orders.dispatchForShipment(tenantId, orderId, shipmentId);
        shipment.dispatch();
        events.publishEvent(new ShipmentActivity("SHIPMENT_DISPATCHED", tenantId, shipmentId, orderId));
        return ShipmentResponse.from(shipment);
    }

    /**
     * This is intentionally synchronous, not AFTER_COMMIT: failure must roll back the order cancellation.
     * MANDATORY also prevents accidental execution without the order's transaction and row lock.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPreparedShipment(OrderCancellation cancellation) {
        var existing = shipments.findOrderForUpdate(cancellation.orderId(), cancellation.tenantId());
        if (existing.isEmpty() || "CANCELLED".equals(existing.get().getStatus())) return;
        var shipment = existing.get();
        if (!"PREPARED".equals(shipment.getStatus())) {
            throw new ShipmentConflictException("Dispatched shipments cannot be cancelled");
        }
        shipment.cancel();
        events.publishEvent(new ShipmentActivity("SHIPMENT_CANCELLED", cancellation.tenantId(), shipment.getId(), shipment.getOrderId()));
    }

    @Transactional(readOnly = true)
    public ShipmentResponse get(UUID tenantId, UUID shipmentId) {
        log.debug("Shipment lookup: tenantId={} shipmentId={}", tenantId, shipmentId);
        return ShipmentResponse.from(shipments.findByIdAndTenantId(shipmentId, tenantId)
                .orElseThrow(ShipmentNotFoundException::new));
    }

    /** Bounded, tenant-scoped pages; ID gives a stable tie-breaker for equal creation times. */
    @Transactional(readOnly = true)
    public ShipmentPage list(UUID tenantId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Page must be nonnegative and size between 1 and 100");
        var result = shipments.findByTenantId(tenantId, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new ShipmentPage(result.getContent().stream().map(ShipmentResponse::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }
}
