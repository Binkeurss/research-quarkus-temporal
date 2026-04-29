package org.acme.temporal.product.publication.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface ProductPublicationActivities {

    @ActivityMethod
    void validateProduct(Long productId);

    @ActivityMethod
    void markProcessing(Long productId);

    @ActivityMethod
    void simulateAssetProcessing(Long productId);

    @ActivityMethod
    void markPublished(Long productId);

    @ActivityMethod
    void markFailed(Long productId, String reason);

    @ActivityMethod
    void sendPublicationNotification(Long productId);
}