package org.acme.temporal.product.publication.workflows;

public record ProductPublicationResult(
        Long productId,
        String finalStatus,
        String message
) {
}