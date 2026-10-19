package com.flowops.shipment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** A full-order shipment: the order ID is its retry key; carrier is an operator label, not an integration. */
public record CreateShipmentRequest(@NotNull(message = "Order ID is required") UUID orderId,
                                    @NotBlank(message = "Carrier is required") @Size(max = 80) String carrier) {
    public CreateShipmentRequest { carrier = carrier == null ? null : carrier.trim(); }
}
