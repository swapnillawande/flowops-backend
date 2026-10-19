package com.flowops.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

/** Fulfillment input; requestId is retained on retries, while tenant identity comes from the server. */
public record CreateOrderRequest(
        @NotNull(message = "Request ID is required") UUID requestId,
        @NotNull(message = "Warehouse ID is required") UUID warehouseId,
        @NotBlank(message = "Customer reference is required") @Size(max = 100) String customerReference,
        @NotEmpty(message = "At least one item is required") @Size(max = 50) List<@NotNull @Valid Item> items
) {
    public CreateOrderRequest { customerReference = customerReference == null ? null : customerReference.trim(); }
    /** A product may appear once; quantities are positive whole units. */
    public record Item(@NotNull UUID productId, @NotNull @Positive Integer quantity) {}
}
