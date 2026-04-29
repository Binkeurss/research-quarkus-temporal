// temporal/email/notification/workflows/EmailNotificationWorkflowImpl.java
package org.acme.temporal.email.notification.workflows;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.acme.temporal.email.notification.activities.EmailActivities;

import java.time.Duration;

public class EmailNotificationWorkflowImpl implements EmailNotificationWorkflow {

    private String currentStep = "STARTED";

    // Activity options RIÊNG cho sendEmail - timeout cao hơn vì SMTP chậm
    private final EmailActivities emailActivities = Workflow.newActivityStub(
            EmailActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(3))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(5)
                            // Lỗi business logic → không retry
                            .setDoNotRetry(
                                    "java.lang.IllegalArgumentException"
                            )
                            .build())
                    .build()
    );

    @Override
    public EmailNotificationResult send(EmailNotificationCommand command) {
        try {
            currentStep = "PREPARING_TEMPLATE";
            emailActivities.prepareEmailTemplate(
                    command.productId(), command.emailType()
            );

            // Demo: Child workflow cũng có thể sleep
            currentStep = "WAITING_BEFORE_SEND";
            System.out.println("[EMAIL WORKFLOW] Waiting 5s before sending...");
            Workflow.sleep(Duration.ofSeconds(5));

            currentStep = "SENDING_EMAIL";
            emailActivities.sendEmail(
                    command.productId(),
                    command.recipientEmail(),
                    command.emailType()
            );

            currentStep = "LOGGING";
            emailActivities.logEmailSent(command.productId(), command.emailType());

            currentStep = "COMPLETED";
            return new EmailNotificationResult(
                    command.productId(),
                    command.emailType(),
                    "SUCCESS",
                    "Email sent to " + command.recipientEmail()
            );

        } catch (Exception e) {
            currentStep = "FAILED";
            return new EmailNotificationResult(
                    command.productId(),
                    command.emailType(),
                    "FAILED",
                    e.getMessage()
            );
        }
    }

    @Override
    public String currentStep() {
        return currentStep;
    }
}