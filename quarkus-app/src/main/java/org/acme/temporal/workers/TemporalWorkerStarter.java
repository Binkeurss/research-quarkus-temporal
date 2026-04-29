package org.acme.temporal.workers;

import io.quarkus.arc.All;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class TemporalWorkerStarter {

    @Inject
    WorkflowClient workflowClient;

    @Inject
    @All
    List<TemporalWorkerRegistrar> registrars;

    private WorkerFactory workerFactory;

    void onStart(@Observes StartupEvent event) {
        workerFactory = WorkerFactory.newInstance(workflowClient);

        for (TemporalWorkerRegistrar registrar : registrars) {
            registrar.register(workerFactory);
        }

        workerFactory.start();

        System.out.println("Temporal WorkerFactory started. Registrars count = " + registrars.size());
    }

    void onStop(@Observes ShutdownEvent event) {
        if (workerFactory != null) {
            workerFactory.shutdown();
            System.out.println("Temporal WorkerFactory stopped.");
        }
    }
}