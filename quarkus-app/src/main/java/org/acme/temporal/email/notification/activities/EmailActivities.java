package org.acme.temporal.email.notification.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface EmailActivities {

    @ActivityMethod
    void prepareEmailTemplate(Long productId, String emailType);

    // Activity này sẽ sleep thật sự để demo timeout/retry
    @ActivityMethod
    void sendEmail(Long productId, String recipientEmail, String emailType);

    @ActivityMethod
    void logEmailSent(Long productId, String emailType);
}