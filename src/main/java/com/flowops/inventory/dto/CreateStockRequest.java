package com.flowops.inventory.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.UUID;

public record CreateStockRequest(
        @NotNull(message = "Product ID is required") UUID productId,
        @NotNull(message = "Initial quantity is required")
        @PositiveOrZero(message = "Initial quantity must not be negative") Integer initialQuantity
) {}
