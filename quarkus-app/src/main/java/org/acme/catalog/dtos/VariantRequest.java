package org.acme.catalog.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.util.List;

public class VariantRequest {

    @NotBlank
    public String skuCode;

    public String variantName;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    public BigDecimal price;

    @NotNull
    @PositiveOrZero
    public Integer stock;

    @Valid
    public List<AttributeRequest> attributes;
}
