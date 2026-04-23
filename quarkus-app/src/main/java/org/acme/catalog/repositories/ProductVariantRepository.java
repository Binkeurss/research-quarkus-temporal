package org.acme.catalog.repositories;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.catalog.entities.Product;
import org.acme.catalog.entities.ProductVariant;

import java.math.BigDecimal;

@ApplicationScoped
public class ProductVariantRepository implements PanacheRepository<ProductVariant> {

    public boolean existsBySkuCode(String skuCode) {
        return count("skuCode", skuCode) > 0;
    }

    public ProductVariant create(
            Product product,
            String skuCode,
            String variantName,
            BigDecimal price,
            Integer stock
    ) {
        ProductVariant variant = new ProductVariant();
        variant.product = product;
        variant.skuCode = skuCode;
        variant.variantName = variantName;
        variant.price = price;
        variant.stock = stock != null ? stock : 0;
        persist(variant);
        return variant;
    }

    public void deleteByProduct(Product product) {
        delete("product", product);
    }
}