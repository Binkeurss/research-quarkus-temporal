package org.acme.temporal.product.publication.workflows;

public record ProductPublicationCommand(
        Long productId,
        long reviewDelaySeconds,
        long processingDelaySeconds
) {
}