// temporal/email/notification/workflows/EmailNotificationCommand.java
package org.acme.temporal.email.notification.workflows;

public record EmailNotificationCommand(
        Long productId,
        String productName,
        String recipientEmail,
        String emailType,           // PUBLICATION, REJECTION, etc.
        int retryAttemptSimulation  // để demo retry
) {}