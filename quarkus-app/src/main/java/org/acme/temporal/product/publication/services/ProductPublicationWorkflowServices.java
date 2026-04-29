package org.acme.temporal.product.publication.services;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.enterprise.context.ApplicationScoped;
import org.acme.temporal.configs.TemporalConfig;
import org.acme.temporal.product.publication.ProductPublicationConfig;
import org.acme.temporal.product.publication.workflows.ProductPublicationCommand;
import org.acme.temporal.product.publication.workflows.ProductPublicationWorkflow;

import java.time.Duration;

@ApplicationScoped
public class ProductPublicationWorkflowServices {

    private final WorkflowClient workflowClient;
    private final TemporalConfig temporalConfig;
    private final ProductPublicationConfig publicationConfig;

    public ProductPublicationWorkflowServices(
            WorkflowClient workflowClient,
            TemporalConfig temporalConfig,
            ProductPublicationConfig publicationConfig
    ) {
        this.workflowClient = workflowClient;
        this.temporalConfig = temporalConfig;
        this.publicationConfig = publicationConfig;
    }

    public String start(Long productId, Long reviewDelaySeconds, Long processingDelaySeconds) {
        TemporalConfig.WorkerConfig productWorkerConfig =
                temporalConfig.workers().get("product");

        if (productWorkerConfig == null || !productWorkerConfig.enabled()) {
            throw new IllegalStateException("Product temporal worker is not configured or disabled");
        }

        String workflowId = "product-publication-" + productId;

        ProductPublicationWorkflow workflow = workflowClient.newWorkflowStub(
                ProductPublicationWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(productWorkerConfig.taskQueue())
//                        .setTaskQueue("OTHER_QUEUE")
                        .setWorkflowRunTimeout(Duration.ofSeconds(publicationConfig.workflowTimeoutSeconds()))
                        .build()
        );

        ProductPublicationCommand command = new ProductPublicationCommand(
                productId,
                reviewDelaySeconds != null
                        ? reviewDelaySeconds
                        : publicationConfig.reviewDelaySeconds(),
                processingDelaySeconds != null
                        ? processingDelaySeconds
                        : publicationConfig.processingDelaySeconds()
        );

        WorkflowClient.start(workflow::publish, command);

        return workflowId;
    }

    public String getCurrentStep(String workflowId) {
        ProductPublicationWorkflow workflow = workflowClient.newWorkflowStub(
                ProductPublicationWorkflow.class,
                workflowId
        );

        return workflow.currentStep();
    }
}
