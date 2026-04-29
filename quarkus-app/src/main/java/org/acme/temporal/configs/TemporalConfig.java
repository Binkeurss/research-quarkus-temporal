package org.acme.temporal.configs;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.Map;

@ConfigMapping(prefix = "temporal")
public interface TemporalConfig {

    @WithDefault("localhost:7233")
    String target();

    @WithDefault("default")
    String namespace();

    Map<String, WorkerConfig> workers();

    interface WorkerConfig {

        @WithDefault("true")
        boolean enabled();

        @WithName("task-queue")
        String taskQueue();

        @WithDefault("3")
        @WithName("max-concurrent-workflow-tasks")
        int maxConcurrentWorkflowTasks();

        @WithDefault("3")
        @WithName("max-concurrent-activities")
        int maxConcurrentActivities();

        @WithDefault("2")
        @WithName("workflow-task-pollers")
        int workflowTaskPollers();

        @WithDefault("2")
        @WithName("activity-task-pollers")
        int activityTaskPollers();
    }
}
