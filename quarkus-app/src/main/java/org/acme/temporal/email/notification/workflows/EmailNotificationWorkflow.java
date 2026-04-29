package org.acme.temporal.email.notification.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.QueryMethod;

@WorkflowInterface
public interface EmailNotificationWorkflow {

    @WorkflowMethod
    EmailNotificationResult send(EmailNotificationCommand command);

    @QueryMethod
    String currentStep();
}