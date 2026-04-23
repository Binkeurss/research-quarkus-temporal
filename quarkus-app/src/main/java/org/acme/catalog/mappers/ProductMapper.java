package org.acme.catalog.mappers;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.catalog.dtos.ProductResponse;
import org.acme.catalog.entities.Product;
import org.acme.catalog.entities.ProductVariant;

import java.util.stream.Collectors;

@ApplicationScoped
public class ProductMapper {

    public ProductResponse toResponse(Product product) {
        ProductResponse response = new ProductResponse();
        response.id = product.id;
        response.name = product.name;
        response.slug = product.slug;
        response.description = product.description;
        response.coverImage = product.coverImage;
        response.status = product.status;
        response.createdAt = product.createdAt;
        response.updatedAt = product.updatedAt;

        response.variants = product.variants.stream()
                .map(this::toVariantResponse)
                .collect(Collectors.toList());

        return response;
    }

    private ProductResponse.VariantResponse toVariantResponse(ProductVariant variant) {
        ProductResponse.VariantResponse response = new ProductResponse.VariantResponse();
        response.id = variant.id;
        response.skuCode = variant.skuCode;
        response.variantName = variant.variantName;
        response.price = variant.price;
        response.stock = variant.stock;
        response.attributes = variant.attributes.stream()
                .map(a -> a.attributeValue.attribute.name + ": " + a.attributeValue.value)
                .collect(Collectors.toList());
        return response;
    }
}