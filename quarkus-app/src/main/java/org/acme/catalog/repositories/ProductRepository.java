package org.acme.catalog.repositories;

import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.catalog.entities.Product;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ProductRepository implements PanacheRepository<Product> {

    public Optional<Product> findBySlugOptional(String slug) {
        return find("slug", slug).firstResultOptional();
    }

    public boolean existsBySlug(String slug) {
        return count("slug", slug) > 0;
    }

    public boolean existsBySlugAndIdNot(String slug, Long id) {
        return count("slug = ?1 and id <> ?2", slug, id) > 0;
    }

    public Product create(
            String name,
            String slug,
            String description,
            String coverImage,
            String status
    ) {
        Product product = new Product();
        product.name = name;
        product.slug = slug;
        product.description = description;
        product.coverImage = coverImage;
        product.status = status != null ? status : "ACTIVE";
        persist(product);
        return product;
    }

    public Product update(
            Product product,
            String name,
            String slug,
            String description,
            String coverImage,
            String status
    ) {
        product.name = name;
        product.slug = slug;
        product.description = description;
        product.coverImage = coverImage;
        product.status = status != null ? status : product.status;
        return product;
    }

    public List<Product> findAfterCursor(Long cursor, int limit) {
        if (cursor == null) {
            return find("order by id asc")
                    .page(0, limit)
                    .list();
        }

        return find("id > ?1 order by id asc", cursor)
                .page(0, limit)
                .list();
    }
}