package org.acme.catalog.repositories;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.catalog.entities.Attribute;

import java.util.Optional;

@ApplicationScoped
public class AttributeRepository implements PanacheRepository<Attribute> {

    public Optional<Attribute> findByNameOptional(String name) {
        return find("name", name).firstResultOptional();
    }

    public Attribute findOrCreate(String name) {
        return findByNameOptional(name).orElseGet(() -> {
            Attribute attribute = new Attribute();
            attribute.name = name;
            persist(attribute);
            return attribute;
        });
    }
}