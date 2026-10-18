package com.flowops.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** The client keeps requestId stable on retries; every item must reference a distinct product. */
public record CreateReservationRequest(
        @NotNull(message = "Request ID is required") UUID requestId,
        @NotEmpty(message = "At least one item is required")
        @Size(max = 50, message = "A reservation supports up to 50 items")
        List<@NotNull @Valid Item> items
) {
    /** Quantity is a positive whole number of units, not a physical stock adjustment. */
    public record Item(@NotNull(message = "Product ID is required") UUID productId,
                       @NotNull(message = "Quantity is required")
                       @Positive(message = "Quantity must be positive") Integer quantity) {}
}
