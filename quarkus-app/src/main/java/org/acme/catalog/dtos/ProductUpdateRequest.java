package org.acme.catalog.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class ProductUpdateRequest {

    @NotBlank
    public String name;

    @NotBlank
    public String slug;

    public String description;

    public String coverImage;

    public String status;

    @Valid
    public List<VariantRequest> variants;
}