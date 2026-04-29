package org.acme.temporal.product.publication.workflows;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.acme.temporal.product.publication.activities.ProductPublicationActivities;

import java.time.Duration;

public class ProductPublicationWorkflowImpl implements ProductPublicationWorkflow {

    private String currentStep = "STARTED";

    private final ProductPublicationActivities activities = Workflow.newActivityStub(
            ProductPublicationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(3)
                            .build())
                    .build()
    );

    @Override
    public ProductPublicationResult publish(ProductPublicationCommand command) {
        try {
            currentStep = "VALIDATING_PRODUCT";
            activities.validateProduct(command.productId());

            currentStep = "MARKING_PROCESSING";
            activities.markProcessing(command.productId());

            currentStep = "WAITING_FOR_REVIEW";
            Workflow.sleep(Duration.ofSeconds(command.reviewDelaySeconds()));

            currentStep = "PROCESSING_ASSETS";
            activities.simulateAssetProcessing(command.productId());

            currentStep = "WAITING_FOR_PROCESSING";
            Workflow.sleep(Duration.ofSeconds(command.processingDelaySeconds()));

            currentStep = "MARKING_PUBLISHED";
            activities.markPublished(command.productId());

            currentStep = "SENDING_NOTIFICATION";
            activities.sendPublicationNotification(command.productId());

            currentStep = "PUBLISHED";

            return new ProductPublicationResult(
                    command.productId(),
                    "PUBLISHED",
                    "Product published successfully"
            );
        } catch (Exception e) {
            currentStep = "FAILED";
            activities.markFailed(command.productId(), e.getMessage());
            throw e;
        }
    }

    @Override
    public String currentStep() {
        return currentStep;
    }
}