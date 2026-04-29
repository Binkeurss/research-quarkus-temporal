package org.acme.temporal.email.notification.workflows;

public record EmailNotificationResult(
        Long productId,
        String emailType,
        String status,
        String message
) {}