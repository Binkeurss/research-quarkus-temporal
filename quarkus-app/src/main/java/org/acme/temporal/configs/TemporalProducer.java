package org.acme.temporal.configs;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class TemporalProducer {

    private final TemporalConfig temporalConfig;

    public TemporalProducer(TemporalConfig temporalConfig) {
        this.temporalConfig = temporalConfig;
    }

    @Produces
    @ApplicationScoped
    WorkflowServiceStubs workflowServiceStubs() {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalConfig.target())
                .build();

        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Produces
    @ApplicationScoped
    WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs) {
        WorkflowClientOptions options = WorkflowClientOptions.newBuilder()
                .setNamespace(temporalConfig.namespace())
                .build();

        return WorkflowClient.newInstance(serviceStubs, options);
    }

    void shutdownWorkflowServiceStubs(@Disposes WorkflowServiceStubs serviceStubs) {
        serviceStubs.shutdown();
    }
}