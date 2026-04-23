package org.acme.catalog.repositories;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.catalog.entities.Attribute;
import org.acme.catalog.entities.AttributeValue;

import java.util.Optional;

@ApplicationScoped
public class AttributeValueRepository implements PanacheRepository<AttributeValue> {

    public Optional<AttributeValue> findByAttributeAndValue(Attribute attribute, String value) {
        return find("attribute = ?1 and value = ?2", attribute, value).firstResultOptional();
    }

    public AttributeValue findOrCreate(Attribute attribute, String value) {
        return findByAttributeAndValue(attribute, value).orElseGet(() -> {
            AttributeValue attributeValue = new AttributeValue();
            attributeValue.attribute = attribute;
            attributeValue.value = value;
            persist(attributeValue);
            return attributeValue;
        });
    }
}