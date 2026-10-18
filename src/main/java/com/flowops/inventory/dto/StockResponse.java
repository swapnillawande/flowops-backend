package com.flowops.inventory.dto;

import com.flowops.inventory.entity.Stock;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Physical, held, and available units are reported separately so callers can explain fulfillment capacity. */
public record StockResponse(UUID id, UUID warehouseId, UUID productId, int onHand,
                            int reserved, int available, long version, OffsetDateTime createdAt) {
    public static StockResponse from(Stock stock) {
        return new StockResponse(stock.getId(), stock.getWarehouseId(), stock.getProductId(),
                stock.getOnHand(), stock.getReserved(), stock.getAvailable(), stock.getVersion(),
                stock.getCreatedAt());
    }
}
