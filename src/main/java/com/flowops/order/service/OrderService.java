package com.flowops.order.service;

import com.flowops.inventory.dto.CreateReservationRequest;
import com.flowops.inventory.service.ReservationService;
import com.flowops.order.dto.CreateOrderRequest;
import com.flowops.order.dto.OrderResponse;
import com.flowops.order.entity.CustomerOrder;
import com.flowops.order.entity.OrderItem;
import com.flowops.order.event.OrderActivity;
import com.flowops.order.event.OrderCancellation;
import com.flowops.order.exception.OrderConflictException;
import com.flowops.order.exception.OrderNotFoundException;
import com.flowops.order.repository.OrderItemRepository;
import com.flowops.order.repository.OrderRepository;
import com.flowops.product.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Modular-monolith fulfillment: order and inventory join one transaction; no distributed saga yet. */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ProductService products;
    private final ReservationService inventory;
    private final ApplicationEventPublisher events;

    public OrderService(OrderRepository orders, OrderItemRepository items, ProductService products,
                        ReservationService inventory, ApplicationEventPublisher events) {
        this.orders = orders;
        this.items = items;
        this.products = products;
        this.inventory = inventory;
        this.events = events;
    }

    public record Result(OrderResponse response, boolean created) {}
    /** Explicit pagination metadata avoids exposing Spring persistence objects through the API. */
    public record OrderPage(List<OrderResponse> content, int page, int size, long totalElements, int totalPages) {}

    /** Request claims serialize concurrent retries before any stock is held. A failure rolls everything back. */
    @Transactional
    public Result create(UUID tenantId, CreateOrderRequest request) {
        Map<UUID, Integer> requested = new TreeMap<>();
        for (var item : request.items()) {
            if (requested.put(item.productId(), item.quantity()) != null) {
                throw new IllegalArgumentException("Each product must appear only once in an order");
            }
        }
        int inserted = orders.claimRequest(UUID.randomUUID(), tenantId, request.requestId(),
                request.warehouseId(), request.customerReference());
        CustomerOrder order = orders.findRequestForUpdate(tenantId, request.requestId()).orElseThrow();
        if (inserted == 0) {
            var existingItems = items.findByTenantIdAndOrderId(tenantId, order.getId());
            Map<UUID, Integer> original = new TreeMap<>();
            existingItems.forEach(item -> original.put(item.getProductId(), item.getQuantity()));
            if (!order.getWarehouseId().equals(request.warehouseId())
                    || !order.getCustomerReference().equals(request.customerReference()) || !original.equals(requested)) {
                throw new OrderConflictException();
            }
            log.debug("Order replay: tenantId={} orderId={}", tenantId, order.getId());
            return new Result(OrderResponse.from(order, existingItems), false);
        }

        // Snapshot names/SKUs through the catalog's service boundary, never by querying its tables.
        var snapshots = requested.entrySet().stream().map(item -> {
            var product = products.getProduct(tenantId, item.getKey());
            return new OrderItem(tenantId, order.getId(), product.getId(), product.getSku(), product.getName(), item.getValue());
        }).toList();
        var holdRequest = new CreateReservationRequest(order.getId(), requested.entrySet().stream()
                .map(item -> new CreateReservationRequest.Item(item.getKey(), item.getValue())).toList());
        var hold = inventory.reserveForOrder(tenantId, request.warehouseId(), order.getId(), holdRequest).response();
        order.confirm(hold.id());
        var savedItems = items.saveAll(snapshots);
        events.publishEvent(new OrderActivity("ORDER_CONFIRMED", tenantId, order.getId()));
        return new Result(OrderResponse.from(order, savedItems), true);
    }

    /** Lock order -> reservation -> stock. Cancellation and returning the held units commit together. */
    @Transactional
    public OrderResponse cancel(UUID tenantId, UUID orderId) {
        CustomerOrder order = orders.findForUpdate(orderId, tenantId).orElseThrow(OrderNotFoundException::new);
        if ("CANCELLED".equals(order.getStatus())) {
            log.debug("Order cancellation replay: tenantId={} orderId={}", tenantId, orderId);
            return response(tenantId, order);
        }
        if (!"CONFIRMED".equals(order.getStatus())) {
            throw new OrderConflictException("Only confirmed orders can be cancelled; dispatched orders require a return workflow");
        }
        inventory.releaseForOrder(tenantId, order.getWarehouseId(), order.getReservationId(), orderId);
        order.cancel();
        // Synchronous listeners join this transaction; shipment failure rolls cancellation back too.
        events.publishEvent(new OrderCancellation(tenantId, orderId));
        events.publishEvent(new OrderActivity("ORDER_CANCELLED", tenantId, orderId));
        return response(tenantId, order);
    }

    /** Shipment mutations acquire this lock before touching their own rows or inventory. */
    @Transactional
    public OrderResponse lockForFulfillment(UUID tenantId, UUID orderId) {
        return response(tenantId, orders.findForUpdate(orderId, tenantId).orElseThrow(OrderNotFoundException::new));
    }

    /** No public order-dispatch HTTP command: shipment dispatch owns this orchestration. */
    @Transactional
    public void dispatchForShipment(UUID tenantId, UUID orderId, UUID shipmentId) {
        var order = orders.findForUpdate(orderId, tenantId).orElseThrow(OrderNotFoundException::new);
        if (!"CONFIRMED".equals(order.getStatus())) {
            throw new OrderConflictException("Only confirmed orders can be dispatched");
        }
        inventory.consumeForOrder(tenantId, order.getWarehouseId(), order.getReservationId(), orderId, shipmentId);
        order.dispatch();
        events.publishEvent(new OrderActivity("ORDER_DISPATCHED", tenantId, orderId));
    }

    /** Called only by shipment progression, in the same transaction as its history entry. */
    @Transactional
    public void deliverForShipment(UUID tenantId, UUID orderId) {
        var order = orders.findForUpdate(orderId, tenantId).orElseThrow(OrderNotFoundException::new);
        if (!"DISPATCHED".equals(order.getStatus())) throw new OrderConflictException("Only dispatched orders can be delivered");
        order.deliver();
        events.publishEvent(new OrderActivity("ORDER_DELIVERED", tenantId, orderId));
    }

    @Transactional(readOnly = true)
    public OrderResponse get(UUID tenantId, UUID orderId) {
        log.debug("Order lookup: tenantId={} orderId={}", tenantId, orderId);
        return response(tenantId, orders.findByIdAndTenantId(orderId, tenantId).orElseThrow(OrderNotFoundException::new));
    }

    /** Bounded pages keep list responses manageable; ID resolves ties in creation timestamps. */
    @Transactional(readOnly = true)
    public OrderPage list(UUID tenantId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Page must be nonnegative and size must be between 1 and 100");
        }
        var result = orders.findByTenantId(tenantId, PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
        return new OrderPage(result.getContent().stream().map(order -> response(tenantId, order)).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    private OrderResponse response(UUID tenantId, CustomerOrder order) {
        return OrderResponse.from(order, items.findByTenantIdAndOrderId(tenantId, order.getId()));
    }
}
