package com.flowops.shipment.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

/** A stable request ID makes retries safe; operational notes are stored in history but never logged. */
public record UpdateShipmentStatusRequest(@NotNull UUID requestId, @NotNull Status status,
                                          @NotBlank @Size(max = 250) String note) {
    public enum Status { IN_TRANSIT, DELIVERED }
    public UpdateShipmentStatusRequest { note = note == null ? null : note.trim(); }
}
