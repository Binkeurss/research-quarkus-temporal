package org.acme.catalog.entities;

import jakarta.persistence.*;
import org.acme.common.base.BaseEntity;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products")
public class Product extends BaseEntity {

    @Column(nullable = false, length = 255)
    public String name;

    @Column(nullable = false, unique = true, length = 255)
    public String slug;

    @Column(columnDefinition = "TEXT")
    public String description;

    public String coverImage;

    @Column(nullable = false)
    public String status = "ACTIVE";

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<ProductVariant> variants = new ArrayList<>();
}