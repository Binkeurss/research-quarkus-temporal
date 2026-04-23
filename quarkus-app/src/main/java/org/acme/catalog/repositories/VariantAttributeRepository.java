package org.acme.catalog.repositories;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.catalog.entities.AttributeValue;
import org.acme.catalog.entities.ProductVariant;
import org.acme.catalog.entities.VariantAttribute;

@ApplicationScoped
public class VariantAttributeRepository implements PanacheRepository<VariantAttribute> {

    public VariantAttribute create(ProductVariant variant, AttributeValue attributeValue) {
        VariantAttribute variantAttribute = new VariantAttribute();
        variantAttribute.variant = variant;
        variantAttribute.attributeValue = attributeValue;
        persist(variantAttribute);
        return variantAttribute;
    }
}