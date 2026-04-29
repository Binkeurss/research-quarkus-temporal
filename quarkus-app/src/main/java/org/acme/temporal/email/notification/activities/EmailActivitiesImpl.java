// temporal/email/notification/activities/EmailActivitiesImpl.java
package org.acme.temporal.email.notification.activities;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class EmailActivitiesImpl implements EmailActivities {

    @Override
    public void prepareEmailTemplate(Long productId, String emailType) {
        System.out.println("[EMAIL] Preparing template: " + emailType
                + " for product: " + productId);
        // Simulate template rendering - 2 giây thật
        sleep(2000);
        System.out.println("[EMAIL] Template prepared.");
    }

    @Override
    public void sendEmail(Long productId, String recipientEmail, String emailType) {
        ActivityExecutionContext ctx = Activity.getExecutionContext();
        int attempt = ctx.getInfo().getAttempt();

        System.out.println("[EMAIL] Sending email attempt #" + attempt
                + " to: " + recipientEmail
                + " type: " + emailType);

        // Demo retry: 2 lần đầu fail, lần 3 thành công
        if (attempt < 3) {
            sleep(3000); // Giả lập SMTP server chậm
            throw new RuntimeException(
                    "[EMAIL] SMTP server timeout on attempt #" + attempt
                            + " (intentional for demo)"
            );
        }

        // Lần thứ 3: thành công
        sleep(1000);
        System.out.println("[EMAIL] Email sent successfully on attempt #" + attempt);
    }

    @Override
    public void logEmailSent(Long productId, String emailType) {
        System.out.println("[EMAIL] Logging email sent: "
                + emailType + " for product: " + productId);
        sleep(500);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}