// temporal/product/publication/workflows/ProductPublicationWorkflowImpl.java
package org.acme.temporal.product.publication.workflows;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import org.acme.temporal.email.notification.workflows.EmailNotificationCommand;
import org.acme.temporal.email.notification.workflows.EmailNotificationResult;
import org.acme.temporal.email.notification.workflows.EmailNotificationWorkflow;
import org.acme.temporal.product.publication.activities.ProductPublicationActivities;

import java.time.Duration;

public class ProductPublicationWorkflowImpl implements ProductPublicationWorkflow {

    private String currentStep = "STARTED";

    private final ProductPublicationActivities activities = Workflow.newActivityStub(
            ProductPublicationActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(3)
                            .setDoNotRetry(
                                    "java.lang.IllegalStateException",
                                    "jakarta.ws.rs.NotFoundException"
                            )
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

            // === DEMO CRASH POINT ===
            // Sau bước này, bạn Ctrl+C app
            // Temporal sẽ tiếp tục từ đây khi app khởi động lại
            currentStep = "WAITING_FOR_REVIEW";
            System.out.println("[WORKFLOW] Sleeping for review: "
                    + command.reviewDelaySeconds() + "s — NOW IS A GOOD TIME TO CTRL+C!");
            Workflow.sleep(Duration.ofSeconds(command.reviewDelaySeconds()));

            currentStep = "PROCESSING_ASSETS";
            activities.simulateAssetProcessing(command.productId());

            currentStep = "WAITING_FOR_PROCESSING";
            System.out.println("[WORKFLOW] Sleeping for processing: "
                    + command.processingDelaySeconds() + "s");
            Workflow.sleep(Duration.ofSeconds(command.processingDelaySeconds()));

            currentStep = "MARKING_PUBLISHED";
            activities.markPublished(command.productId());

            // === CHILD WORKFLOW: ASYNC (không chờ kết quả) ===
            // Parent tiếp tục ngay, child chạy độc lập
            currentStep = "STARTING_EMAIL_NOTIFICATION";
            Promise<EmailNotificationResult> emailPromise = startEmailChildAsync(
                    command.productId()
            );

            // Parent có thể làm việc khác trong khi child đang chạy
            currentStep = "SENDING_INTERNAL_NOTIFICATION";
            activities.sendPublicationNotification(command.productId());

            // Chờ child hoàn thành (có thể không chờ nếu dùng ABANDON policy)
            currentStep = "WAITING_FOR_EMAIL_CHILD";
            EmailNotificationResult emailResult = emailPromise.get();
            System.out.println("[WORKFLOW] Email child result: " + emailResult.status());

            currentStep = "PUBLISHED";
            return new ProductPublicationResult(
                    command.productId(),
                    "PUBLISHED",
                    "Product published. Email: " + emailResult.status()
            );

        } catch (Exception e) {
            currentStep = "FAILED";
            activities.markFailed(command.productId(), e.getMessage());
            throw e;
        }
    }

    private Promise<EmailNotificationResult> startEmailChildAsync(Long productId) {
        // Child workflow options
        EmailNotificationWorkflow childWorkflow = Workflow.newChildWorkflowStub(
                EmailNotificationWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setWorkflowId("email-notification-product-" + productId
                                + "-" + Workflow.currentTimeMillis())
                        // Task queue của email worker
                        // Có thể là queue riêng hoặc dùng chung
                        .setTaskQueue("PRODUCT_TASK_QUEUE") // dùng chung cho demo
                        .setWorkflowRunTimeout(Duration.ofMinutes(3))
                        // ABANDON: child chạy tiếp dù parent kết thúc/fail
                        // TERMINATE: child bị kill khi parent kết thúc
                        // REQUEST_CANCEL: child nhận cancel signal
                        .setParentClosePolicy(
                                io.temporal.api.enums.v1.ParentClosePolicy
                                        .PARENT_CLOSE_POLICY_ABANDON
                        )
                        .setRetryOptions(RetryOptions.newBuilder()
                                .setMaximumAttempts(2)
                                .build())
                        .build()
        );

        EmailNotificationCommand emailCommand = new EmailNotificationCommand(
                productId,
                "Product #" + productId,
                "admin@example.com",
                "PUBLICATION",
                0
        );

        // Async: trả về Promise, không block
        return Async.function(childWorkflow::send, emailCommand);
    }

    @Override
    public String currentStep() {
        return currentStep;
    }
}