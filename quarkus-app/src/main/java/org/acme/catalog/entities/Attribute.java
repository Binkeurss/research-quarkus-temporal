package org.acme.catalog.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.acme.common.base.BaseEntity;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "attributes")
public class Attribute extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    public String name;

    @OneToMany(mappedBy = "attribute")
    public List<AttributeValue> values = new ArrayList<>();
}
