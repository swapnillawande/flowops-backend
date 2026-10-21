package com.flowops.order.dto;

import com.flowops.order.entity.CustomerOrder;
import com.flowops.order.entity.OrderItem;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Public order snapshot, with the inventory hold identifier for fulfillment diagnostics. */
public record OrderResponse(UUID id, UUID requestId, UUID warehouseId, String customerReference, String status,
                            UUID reservationId, List<Item> items, OffsetDateTime createdAt, OffsetDateTime cancelledAt, OffsetDateTime dispatchedAt, OffsetDateTime deliveredAt) {
    public record Item(UUID productId, String sku, String productName, int quantity) {}
    public static OrderResponse from(CustomerOrder order, List<OrderItem> items) {
        return new OrderResponse(order.getId(), order.getRequestId(), order.getWarehouseId(),
                order.getCustomerReference(), order.getStatus(), order.getReservationId(), items.stream()
                .sorted(Comparator.comparing(OrderItem::getProductId))
                .map(item -> new Item(item.getProductId(), item.getSku(), item.getProductName(), item.getQuantity())).toList(),
                order.getCreatedAt(), order.getCancelledAt(), order.getDispatchedAt(), order.getDeliveredAt());
    }
}
