package org.acme.temporal.product.publication.activities;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import org.acme.catalog.entities.Product;
import org.acme.catalog.repositories.ProductRepository;

@ApplicationScoped
public class ProductPublicationActivitiesImpl implements ProductPublicationActivities {

    private final ProductRepository productRepository;

    public ProductPublicationActivitiesImpl(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Transactional
    public void validateProduct(Long productId) {
        Product product = findProduct(productId);

        if (product.name == null || product.name.isBlank()) {
            throw new IllegalStateException("Product name is required");
        }

        if (product.slug == null || product.slug.isBlank()) {
            throw new IllegalStateException("Product slug is required");
        }

        if (product.variants == null || product.variants.isEmpty()) {
            throw new IllegalStateException("Product must have at least one variant");
        }
    }

    @Override
    @Transactional
    public void markProcessing(Long productId) {
        Product product = findProduct(productId);
        product.status = "PROCESSING";
    }

    @Override
    @Transactional
    public void simulateAssetProcessing(Long productId) {
        Product product = findProduct(productId);

        // Không sleep ở activity.
        // Delay dài nằm trong Workflow.sleep(...).
        System.out.println("Simulating asset processing for product: " + product.id);
    }

    @Override
    @Transactional
    public void markPublished(Long productId) {
        Product product = findProduct(productId);
        product.status = "PUBLISHED";
    }

    @Override
    @Transactional
    public void markFailed(Long productId, String reason) {
        Product product = findProduct(productId);
        product.status = "FAILED";
        System.out.println("Product publication failed. productId=" + productId + ", reason=" + reason);
    }

    @Override
    public void sendPublicationNotification(Long productId) {
        System.out.println("Product publication notification sent. productId=" + productId);
    }

    private Product findProduct(Long productId) {
        return productRepository.findByIdOptional(productId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));
    }
}