package org.acme.catalog.entities;

import jakarta.persistence.*;
import org.acme.common.base.BaseEntity;

@Entity
@Table(
        name = "variant_attributes",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"variant_id", "attribute_value_id"})
        }
)
public class VariantAttribute extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    public ProductVariant variant;

    @ManyToOne(optional = false)
    @JoinColumn(name = "attribute_value_id", nullable = false)
    public AttributeValue attributeValue;
}
