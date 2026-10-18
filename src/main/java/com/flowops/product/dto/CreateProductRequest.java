package com.flowops.product.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Locale;

/** Normalizes SKU and name before validation; BigDecimal preserves exact decimal weights. */
public record CreateProductRequest(
        @NotBlank(message = "SKU is required")
        @Size(max = 64)
        @Pattern(regexp = "[A-Z0-9][A-Z0-9._-]*",
                message = "SKU must start with a letter or digit and contain only letters, digits, dots, underscores or hyphens")
        String sku,
        @NotBlank(message = "Name is required")
        @Size(max = 150)
        String name,
        @NotNull(message = "Weight is required")
        @Positive(message = "Weight must be positive")
        @Digits(integer = 6, fraction = 3, message = "Weight supports up to six integer and three decimal digits")
        BigDecimal weightKg
) {
    public CreateProductRequest {
        sku = sku == null ? null : sku.trim().toUpperCase(Locale.ROOT);
        name = name == null ? null : name.trim();
    }
}
