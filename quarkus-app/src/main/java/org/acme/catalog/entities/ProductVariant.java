package org.acme.catalog.entities;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.acme.common.base.BaseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "product_variants")
public class ProductVariant extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    public Product product;

    @Column(unique = true, length = 100)
    public String skuCode;

    @Column(length = 255)
    public String variantName;

    @Column(nullable = false, precision = 19, scale = 2)
    public BigDecimal price;

    @Column(nullable = false)
    public Integer stock = 0;

    @OneToMany(mappedBy = "variant", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<VariantAttribute> attributes = new ArrayList<>();
}
