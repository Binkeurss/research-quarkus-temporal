package org.acme.temporal.product;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.acme.temporal.configs.TemporalConfig;
import org.acme.temporal.product.publication.activities.ProductPublicationActivitiesImpl;
import org.acme.temporal.product.publication.workflows.ProductPublicationWorkflowImpl;
import org.acme.temporal.workers.TemporalWorkerRegistrar;

@ApplicationScoped
public class ProductTemporalRegistrar implements TemporalWorkerRegistrar {

    @Inject
    TemporalConfig temporalConfig;

    @Inject
    ProductPublicationActivitiesImpl productPublicationActivities;

    @Override
    public void register(WorkerFactory workerFactory) {
        TemporalConfig.WorkerConfig config = temporalConfig.workers().get("product");

        if (config == null) {
            System.out.println("Product temporal worker config not found. Skipping product worker.");
            return;
        }

        if (!config.enabled()) {
            System.out.println("Product temporal worker disabled. Skipping product worker.");
            return;
        }

        WorkerOptions workerOptions = WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskExecutionSize(config.maxConcurrentWorkflowTasks())
                .setMaxConcurrentActivityExecutionSize(config.maxConcurrentActivities())
                .setMaxConcurrentWorkflowTaskPollers(config.workflowTaskPollers())
                .setMaxConcurrentActivityTaskPollers(config.activityTaskPollers())
                .build();

        Worker worker = workerFactory.newWorker(config.taskQueue(), workerOptions);

        worker.registerWorkflowImplementationTypes(
                ProductPublicationWorkflowImpl.class
        );

        worker.registerActivitiesImplementations(
                productPublicationActivities
        );

//        worker.registerWorkflowImplementationTypes(
//                ProductPublicationWorkflowImpl.class,
//                ProductArchiveWorkflowImpl.class
//        );
//
//        worker.registerActivitiesImplementations(
//                productPublicationActivities,
//                productArchiveActivities
//        );

        System.out.println(
                "Product temporal worker registered. Task queue = " + config.taskQueue()
                        + ", workflow concurrency = " + config.maxConcurrentWorkflowTasks()
                        + ", activity concurrency = " + config.maxConcurrentActivities()
        );
    }
}
