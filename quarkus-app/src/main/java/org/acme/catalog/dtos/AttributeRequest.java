package org.acme.catalog.dtos;

import jakarta.validation.constraints.NotBlank;

public class AttributeRequest {

    @NotBlank
    public String name;

    @NotBlank
    public String value;
}