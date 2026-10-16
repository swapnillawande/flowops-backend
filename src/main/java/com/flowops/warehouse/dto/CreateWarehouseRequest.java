
package com.flowops.warehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWarehouseRequest(

    @NotBlank(message = "Name is required")
    @Size(max = 150)
    String name,

    @NotBlank(message = "City is required")
    @Size(max = 100)
    String city,

    @NotBlank(message = "Country code is required")
    @Pattern(
        regexp = "(?i)[A-Z]{2}",
        message = "Country code must contain two letters"
    )
    String countryCode

) {}
