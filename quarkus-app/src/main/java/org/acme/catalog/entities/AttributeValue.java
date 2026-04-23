package org.acme.catalog.entities;

import jakarta.persistence.*;
import org.acme.common.base.BaseEntity;

@Entity
@Table(
        name = "attribute_values",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"attribute_id", "value"})
        }
)
public class AttributeValue extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "attribute_id", nullable = false)
    public Attribute attribute;

    @Column(nullable = false)
    public String value;
}
