package org.acme.catalog.seeders;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.transaction.Transactional;
import org.acme.catalog.entities.Attribute;
import org.acme.catalog.entities.AttributeValue;
import org.acme.catalog.entities.Product;
import org.acme.catalog.entities.ProductVariant;
import org.acme.catalog.entities.VariantAttribute;

import java.math.BigDecimal;

@ApplicationScoped
@IfBuildProfile("dev")
public class DataSeeder {

    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (Product.count() > 0) {
            return;
        }

        Attribute ram = new Attribute();
        ram.name = "RAM";
        ram.persist();

        Attribute ssd = new Attribute();
        ssd.name = "SSD";
        ssd.persist();

        AttributeValue ram8 = createValue(ram, "8GB");
        AttributeValue ram16 = createValue(ram, "16GB");
        AttributeValue ram32 = createValue(ram, "32GB");

        AttributeValue ssd256 = createValue(ssd, "256GB");
        AttributeValue ssd512 = createValue(ssd, "512GB");
        AttributeValue ssd1tb = createValue(ssd, "1TB");

        String[] brands = {"Dell", "HP", "Lenovo", "Asus", "Acer", "Apple"};
        String[][] lines = {
                {"Inspiron", "XPS"},
                {"Pavilion", "Envy"},
                {"IdeaPad", "ThinkPad"},
                {"VivoBook", "ROG"},
                {"Aspire", "Nitro"},
                {"MacBook Air", "MacBook Pro"}
        };

        for (int i = 1; i <= 600; i++) {
            int brandIndex = (i - 1) % brands.length;
            String brand = brands[brandIndex];
            String line = lines[brandIndex][i % 2];

            Product product = new Product();
            product.name = brand + " " + line + " " + String.format("%03d", i);
            product.slug = (brand + "-" + line + "-" + i).toLowerCase().replace(" ", "-");
            product.description = "Demo product " + i + " for pagination, search and filter";
            product.coverImage = "https://picsum.photos/seed/product-" + i + "/400/300";
            product.status = resolveStatus(i);
            product.persist();

            createVariant(product, 1, "8GB / 256GB", new BigDecimal("15990000"), 20, ram8, ssd256);
            createVariant(product, 2, "16GB / 512GB", new BigDecimal("21990000"), 12, ram16, ssd512);
            createVariant(product, 3, "32GB / 1TB", new BigDecimal("29990000"), 5, ram32, ssd1tb);
        }
    }

    private AttributeValue createValue(Attribute attribute, String value) {
        AttributeValue av = new AttributeValue();
        av.attribute = attribute;
        av.value = value;
        av.persist();
        return av;
    }

    private String resolveStatus(int i) {
        if (i % 10 == 0) return "DRAFT";
        if (i % 4 == 0) return "INACTIVE";
        return "ACTIVE";
    }

    private void createVariant(
            Product product,
            int order,
            String variantName,
            BigDecimal price,
            int stock,
            AttributeValue ramValue,
            AttributeValue ssdValue
    ) {
        ProductVariant variant = new ProductVariant();
        variant.product = product;
        variant.skuCode = product.slug.toUpperCase() + "-V" + order;
        variant.variantName = variantName;
        variant.price = price;
        variant.stock = stock;
        variant.persist();

        VariantAttribute ramAttr = new VariantAttribute();
        ramAttr.variant = variant;
        ramAttr.attributeValue = ramValue;
        ramAttr.persist();

        VariantAttribute ssdAttr = new VariantAttribute();
        ssdAttr.variant = variant;
        ssdAttr.attributeValue = ssdValue;
        ssdAttr.persist();

        variant.attributes.add(ramAttr);
        variant.attributes.add(ssdAttr);
        product.variants.add(variant);
    }
}