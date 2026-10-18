package com.flowops.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateStockMovementRequest(
        @NotNull(message = "Request ID is required") UUID requestId,
        @NotNull(message = "Movement type is required") Type type,
        @NotNull(message = "Quantity is required") Integer quantity,
        @NotBlank(message = "Reason is required") @Size(max = 250) String reason
) {
    public enum Type { RECEIPT, ADJUSTMENT }

    public CreateStockMovementRequest {
        reason = reason == null ? null : reason.trim();
    }
}
