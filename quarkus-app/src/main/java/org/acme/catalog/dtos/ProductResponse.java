package org.acme.catalog.dtos;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class ProductResponse {

    public Long id;
    public String name;
    public String slug;
    public String description;
    public String coverImage;
    public String status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
    public List<VariantResponse> variants;

    public static class VariantResponse {
        public Long id;
        public String skuCode;
        public String variantName;
        public BigDecimal price;
        public Integer stock;
        public List<String> attributes;
    }
}