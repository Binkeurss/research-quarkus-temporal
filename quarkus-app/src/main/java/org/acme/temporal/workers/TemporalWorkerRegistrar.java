package org.acme.temporal.workers;

import io.temporal.worker.WorkerFactory;

public interface TemporalWorkerRegistrar {

    void register(WorkerFactory workerFactory);
}