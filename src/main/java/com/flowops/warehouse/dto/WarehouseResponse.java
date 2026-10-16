
package com.flowops.warehouse.dto;

import com.flowops.warehouse.entity.Warehouse;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WarehouseResponse(
    UUID id,
    String name,
    String city,
    String countryCode,
    OffsetDateTime createdAt
) {
    public static WarehouseResponse from(Warehouse warehouse) {
        return new WarehouseResponse(
            warehouse.getId(),
            warehouse.getName(),
            warehouse.getCity(),
            warehouse.getCountryCode(),
            warehouse.getCreatedAt()
        );
    }
}
