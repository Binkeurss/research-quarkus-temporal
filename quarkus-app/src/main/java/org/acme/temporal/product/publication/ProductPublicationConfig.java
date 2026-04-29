package org.acme.temporal.product.publication;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

@ConfigMapping(prefix = "product.publication")
public interface ProductPublicationConfig {

    @WithName("review-delay-seconds")
    @WithDefault("15")
    long reviewDelaySeconds();

    @WithName("processing-delay-seconds")
    @WithDefault("20")
    long processingDelaySeconds();

    @WithName("workflow-timeout-seconds")
    @WithDefault("60")
    long workflowTimeoutSeconds();
}