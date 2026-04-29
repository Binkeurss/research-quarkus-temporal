package org.acme.temporal.product.publication.workflows;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface ProductPublicationWorkflow {

    @WorkflowMethod
    ProductPublicationResult publish(ProductPublicationCommand command);

    @QueryMethod
    String currentStep();
}