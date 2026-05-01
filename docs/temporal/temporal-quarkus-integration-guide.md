# Temporal With Quarkus Beginner Guide

This document is for new backend members who are learning how to use Temporal with a Quarkus service. It explains what Temporal is, why it exists, which problems it solves, the most important Java syntax, and a step-by-step way to integrate Temporal into a Quarkus project.

## 1. What Is Temporal?

Temporal is a platform for building reliable long-running applications.

Normal backend code usually works like this:

```text
HTTP request
    ↓
service method runs
    ↓
database/API/message queue
    ↓
HTTP response
```

That works well for short operations. But many real business processes are not short:

- Create an order
- Reserve inventory
- Charge payment
- Send confirmation email
- Wait for shipping partner response
- Retry failed external APIs
- Cancel or compensate if something fails

These processes may take seconds, minutes, hours, days, or even months. If the service crashes halfway through, normal code can lose the current state unless you manually build a lot of recovery logic.

Temporal solves this by storing workflow execution history outside your application. If your worker crashes, Temporal can replay the workflow and continue from the correct state.

In simple words:

> Temporal lets you write business processes as code, while Temporal handles retries, state recovery, timers, and long-running execution.

## 2. Why Was Temporal Created?

Temporal was created because distributed systems are hard.

When a business process touches many systems, many things can fail:

- Network calls fail
- External APIs timeout
- Workers crash
- Kubernetes restarts pods
- Payment succeeds but the database update fails
- A message is sent twice
- A process needs to wait for a human approval
- A retry should happen later, not immediately

Without Temporal, teams often solve these problems manually with:

- Database status tables
- Cron jobs
- Retry queues
- Message brokers
- Manual compensation logic
- Complex state machines
- Custom recovery scripts

Those solutions can work, but they become difficult to maintain as the process grows.

Temporal was created to make these workflows explicit, durable, testable, and easier to reason about.

## 3. What Problems Does Temporal Solve?

Temporal is useful when you need reliability across multiple steps.

It solves:

- Durable execution: workflow state survives worker crashes.
- Automatic retries: failed activities can retry using clear policies.
- Long-running processes: workflows can run for a long time.
- Timers and delays: wait without keeping a thread busy.
- State recovery: workflow history allows replay.
- Visibility: Temporal Web UI shows workflow status and history.
- Idempotent orchestration: use workflow IDs to avoid duplicate business processes.
- Separation of orchestration and side effects: workflows coordinate, activities perform external work.

Good use cases:

- Order fulfillment
- Payment processing
- User onboarding
- Document approval
- Scheduled reminders
- Data pipelines
- Saga-style distributed transactions
- Retrying unreliable external services

Bad or unnecessary use cases:

- Simple CRUD with one database transaction
- Very small synchronous APIs
- Low-value tasks where failure recovery does not matter
- CPU-heavy batch jobs that do not need durable orchestration

## 4. Temporal Core Concepts

### 4.1 Temporal Service

The Temporal Service is the backend server that stores workflow history, task queues, namespaces, timers, and execution state.

In local development, you can start it with:

```bash
temporal server start-dev
```

Default local endpoints:

```text
Temporal gRPC address: localhost:7233
Temporal Web UI:       http://localhost:8233
Namespace:             default
```

In production, the Temporal Service can be self-hosted or provided by Temporal Cloud.

### 4.2 Workflow

A Workflow is the durable business process.

Example:

```text
Order workflow
    ↓
validate order
    ↓
reserve inventory
    ↓
charge payment
    ↓
send email
```

Workflow code should contain orchestration logic, not direct calls to databases or external APIs.

Important rule:

> Workflow code must be deterministic.

That means when Temporal replays workflow history, the workflow code must make the same decisions from the same history.

Avoid these directly inside workflow code:

- Random values
- Current system time with `System.currentTimeMillis()`
- Direct HTTP calls
- Direct database calls
- Starting native threads
- Non-deterministic collection iteration when order matters

Use Temporal APIs instead:

- `Workflow.currentTimeMillis()`
- `Workflow.sleep(...)`
- Activity calls for external work

### 4.3 Activity

An Activity is a normal unit of work that can interact with the outside world.

Activities are where you put:

- Database writes
- HTTP calls
- Sending email
- Publishing messages
- Calling payment providers
- Reading files
- Any side effect

Activities can be retried automatically. Because of retries, activity logic should be idempotent when possible.

### 4.4 Worker

A Worker is your application process that executes workflow and activity code.

The Worker:

- Connects to the Temporal Service
- Polls a task queue
- Runs workflow tasks
- Runs activity tasks

If no Worker is running, workflows can be started but no work will be completed.

### 4.5 Task Queue

A Task Queue is the queue that connects workflow executions to workers.

Example:

```text
Task queue: ORDER_TASK_QUEUE
```

When starting a workflow, the client chooses the task queue. Workers that poll the same task queue execute that workflow and its activities.

### 4.6 Workflow Client

The Workflow Client starts, signals, queries, and cancels workflow executions.

In a Quarkus REST API, the resource layer often receives an HTTP request and uses the Workflow Client to start a workflow.

### 4.7 Namespace

A Namespace is a logical isolation boundary in Temporal.

For local development, the namespace is usually:

```text
default
```

In real environments, teams often use separate namespaces:

```text
dev
staging
production
```

## 5. Temporal Java Syntax Cheat Sheet

### Workflow Interface

```java
package org.acme.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    String createOrder(CreateOrderCommand command);
}
```

Rules:

- Use `@WorkflowInterface` on the interface.
- Use exactly one `@WorkflowMethod` as the workflow entry point.
- Input and output should be serializable.
- Prefer stable DTOs such as records or simple classes.

### Workflow Implementation

```java
package org.acme.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities = Workflow.newActivityStub(
            OrderActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .build()
    );

    @Override
    public String createOrder(CreateOrderCommand command) {
        String orderId = activities.persistOrder(command);
        activities.sendConfirmationEmail(orderId);
        return orderId;
    }
}
```

Rules:

- Workflow implementation should orchestrate.
- Do not inject normal CDI beans directly into workflow implementation.
- Use activity stubs to call external work.
- Keep workflow logic deterministic.

### Activity Interface

```java
package org.acme.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface OrderActivities {

    @ActivityMethod
    String persistOrder(CreateOrderCommand command);

    @ActivityMethod
    void sendConfirmationEmail(String orderId);
}
```

### Activity Implementation

```java
package org.acme.workflow;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OrderActivitiesImpl implements OrderActivities {

    @Override
    public String persistOrder(CreateOrderCommand command) {
        return "order-001";
    }

    @Override
    public void sendConfirmationEmail(String orderId) {
        System.out.println("Send email for " + orderId);
    }
}
```

Rules:

- Activities can use CDI beans.
- Activities can call databases, REST clients, message brokers, and other services.
- Activities may be retried, so make them idempotent.

### Start Workflow From Java

```java
WorkflowOptions options = WorkflowOptions.newBuilder()
        .setTaskQueue("ORDER_TASK_QUEUE")
        .setWorkflowId("order-" + request.idempotencyKey())
        .build();

OrderWorkflow workflow = workflowClient.newWorkflowStub(OrderWorkflow.class, options);

String orderId = workflow.createOrder(command);
```

This starts the workflow and waits for the workflow result.

For HTTP APIs, it is often better to start asynchronously:

```java
WorkflowClient.start(workflow::createOrder, command);
```

Then return `202 Accepted` to the caller.

## 6. Recommended Quarkus Folder Structure With Temporal

For this project, the current base package is:

```text
org.acme
```

Recommended structure:

```text
quarkus-app/src/main/java/org/acme/
├── api/
│   ├── OrderResource.java
│   ├── CreateOrderRequest.java
│   └── CreateOrderResponse.java
├── application/
│   └── OrderApplicationService.java
├── domain/
│   ├── Order.java
│   └── OrderStatus.java
├── infrastructure/
│   ├── persistence/
│   └── external/
├── temporal/
│   ├── TemporalConfig.java
│   ├── TemporalWorkerStarter.java
│   ├── OrderTaskQueues.java
│   ├── workflow/
│   │   ├── OrderWorkflow.java
│   │   ├── OrderWorkflowImpl.java
│   │   └── CreateOrderCommand.java
│   └── activity/
│       ├── OrderActivities.java
│       └── OrderActivitiesImpl.java
└── config/
```

Package responsibility:

- `api`: HTTP endpoints and request/response DTOs
- `application`: application services that start workflows or coordinate simple use cases
- `domain`: business models and rules
- `infrastructure`: database, external APIs, messaging
- `temporal`: Temporal client, worker, workflows, activities
- `config`: general Quarkus configuration

## 7. Step-by-Step: Add Temporal To This Quarkus Project

### Step 1: Confirm Current Dependency Status

The current `quarkus-app/pom.xml` has:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-arc</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest</artifactId>
</dependency>
```

This is enough for basic Quarkus REST and CDI, but not enough for Temporal.

### Step 2: Add Temporal Dependencies

Add a Temporal version property:

```xml
<temporal.version>1.34.0</temporal.version>
```

Add dependencies:

```xml
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-sdk</artifactId>
    <version>${temporal.version}</version>
</dependency>
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-testing</artifactId>
    <version>${temporal.version}</version>
    <scope>test</scope>
</dependency>
```

Optional but useful for REST JSON APIs:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-jackson</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-hibernate-validator</artifactId>
</dependency>
```

### Step 3: Add Application Properties

Edit:

```text
quarkus-app/src/main/resources/application.properties
```

Add:

```properties
temporal.target=localhost:7233
temporal.namespace=default
temporal.task-queue=ORDER_TASK_QUEUE
```

For production, these values should come from environment variables or Kubernetes config:

```bash
TEMPORAL_TARGET=temporal-frontend.temporal.svc.cluster.local:7233
TEMPORAL_NAMESPACE=production
TEMPORAL_TASK_QUEUE=ORDER_TASK_QUEUE
```

### Step 4: Create Task Queue Constants

Create:

```text
quarkus-app/src/main/java/org/acme/temporal/OrderTaskQueues.java
```

```java
package org.acme.temporal;

public final class OrderTaskQueues {

    public static final String ORDER_TASK_QUEUE = "ORDER_TASK_QUEUE";

    private OrderTaskQueues() {
    }
}
```

Using constants prevents small spelling mistakes between clients and workers.

### Step 5: Create Workflow Input DTO

Create:

```text
quarkus-app/src/main/java/org/acme/temporal/workflow/CreateOrderCommand.java
```

```java
package org.acme.temporal.workflow;

public record CreateOrderCommand(
        String idempotencyKey,
        String productCode,
        int quantity
) {
}
```

Use stable fields. Be careful when changing workflow DTOs after workflows are already running in production.

### Step 6: Create Activity Interface

Create:

```text
quarkus-app/src/main/java/org/acme/temporal/activity/OrderActivities.java
```

```java
package org.acme.temporal.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import org.acme.temporal.workflow.CreateOrderCommand;

@ActivityInterface
public interface OrderActivities {

    @ActivityMethod
    String persistOrder(CreateOrderCommand command);

    @ActivityMethod
    void sendConfirmationEmail(String orderId);
}
```

Activity interfaces describe the side-effect actions that the workflow can request.

### Step 7: Create Activity Implementation

Create:

```text
quarkus-app/src/main/java/org/acme/temporal/activity/OrderActivitiesImpl.java
```

```java
package org.acme.temporal.activity;

import jakarta.enterprise.context.ApplicationScoped;
import org.acme.temporal.workflow.CreateOrderCommand;

@ApplicationScoped
public class OrderActivitiesImpl implements OrderActivities {

    @Override
    public String persistOrder(CreateOrderCommand command) {
        return "order-" + command.idempotencyKey();
    }

    @Override
    public void sendConfirmationEmail(String orderId) {
        System.out.println("Confirmation email sent for " + orderId);
    }
}
```

Later, this class can call real database repositories, email services, or other APIs.

### Step 8: Create Workflow Interface

Create:

```text
quarkus-app/src/main/java/org/acme/temporal/workflow/OrderWorkflow.java
```

```java
package org.acme.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    String createOrder(CreateOrderCommand command);
}
```

The method annotated with `@WorkflowMethod` is the workflow entry point.

### Step 9: Create Workflow Implementation

Create:

```text
quarkus-app/src/main/java/org/acme/temporal/workflow/OrderWorkflowImpl.java
```

```java
package org.acme.temporal.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import org.acme.temporal.activity.OrderActivities;

import java.time.Duration;

public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities = Workflow.newActivityStub(
            OrderActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumAttempts(3)
                            .build())
                    .build()
    );

    @Override
    public String createOrder(CreateOrderCommand command) {
        String orderId = activities.persistOrder(command);
        activities.sendConfirmationEmail(orderId);
        return orderId;
    }
}
```

Important:

- This class is not a normal CDI bean.
- Do not annotate it with `@ApplicationScoped`.
- Register it with the Temporal Worker.
- Keep it deterministic.

### Step 10: Create Temporal Configuration Beans

Create:

```text
quarkus-app/src/main/java/org/acme/temporal/TemporalConfig.java
```

```java
package org.acme.temporal;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class TemporalConfig {

    @ConfigProperty(name = "temporal.target", defaultValue = "localhost:7233")
    String temporalTarget;

    @Produces
    @ApplicationScoped
    WorkflowServiceStubs workflowServiceStubs() {
        WorkflowServiceStubsOptions options = WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalTarget)
                .build();
        return WorkflowServiceStubs.newServiceStubs(options);
    }

    @Produces
    @ApplicationScoped
    WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs) {
        return WorkflowClient.newInstance(serviceStubs);
    }
}
```

This makes `WorkflowClient` injectable in Quarkus services and resources.

### Step 11: Start The Worker When Quarkus Starts

Create:

```text
quarkus-app/src/main/java/org/acme/temporal/TemporalWorkerStarter.java
```

```java
package org.acme.temporal;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.acme.temporal.activity.OrderActivitiesImpl;
import org.acme.temporal.workflow.OrderWorkflowImpl;

@ApplicationScoped
public class TemporalWorkerStarter {

    private final WorkflowClient workflowClient;
    private final OrderActivitiesImpl orderActivities;
    private WorkerFactory workerFactory;

    public TemporalWorkerStarter(
            WorkflowClient workflowClient,
            OrderActivitiesImpl orderActivities
    ) {
        this.workflowClient = workflowClient;
        this.orderActivities = orderActivities;
    }

    void onStart(@Observes StartupEvent event) {
        workerFactory = WorkerFactory.newInstance(workflowClient);

        Worker worker = workerFactory.newWorker(OrderTaskQueues.ORDER_TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(orderActivities);

        workerFactory.start();
    }

    void onStop(@Observes ShutdownEvent event) {
        if (workerFactory != null) {
            workerFactory.shutdown();
        }
    }
}
```

This starts a Temporal Worker inside the Quarkus application process.

For larger production systems, you may separate API pods and Worker pods:

```text
order-api deployment
    starts workflows

order-worker deployment
    runs workflows and activities
```

That separation gives better scaling and operational control.

### Step 12: Start Workflow From A REST API

Create request and response DTOs:

```text
quarkus-app/src/main/java/org/acme/api/CreateOrderRequest.java
quarkus-app/src/main/java/org/acme/api/CreateOrderResponse.java
```

```java
package org.acme.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(
        @NotBlank String idempotencyKey,
        @NotBlank String productCode,
        @Min(1) int quantity
) {
}
```

```java
package org.acme.api;

public record CreateOrderResponse(
        String workflowId
) {
}
```

Create resource:

```text
quarkus-app/src/main/java/org/acme/api/OrderResource.java
```

```java
package org.acme.api;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import jakarta.validation.Valid;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.acme.temporal.OrderTaskQueues;
import org.acme.temporal.workflow.CreateOrderCommand;
import org.acme.temporal.workflow.OrderWorkflow;

@Path("/orders")
public class OrderResource {

    private final WorkflowClient workflowClient;

    public OrderResource(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @POST
    public Response create(@Valid CreateOrderRequest request) {
        String workflowId = "order-" + request.idempotencyKey();

        OrderWorkflow workflow = workflowClient.newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(workflowId)
                        .setTaskQueue(OrderTaskQueues.ORDER_TASK_QUEUE)
                        .build()
        );

        CreateOrderCommand command = new CreateOrderCommand(
                request.idempotencyKey(),
                request.productCode(),
                request.quantity()
        );

        WorkflowClient.start(workflow::createOrder, command);

        return Response.accepted(new CreateOrderResponse(workflowId)).build();
    }
}
```

Why return `202 Accepted`?

The workflow may take time. The API should confirm that the process has started, not pretend the entire business process has already finished.

### Step 13: Run Temporal Locally

Install Temporal CLI, then run:

```bash
temporal server start-dev
```

Open the UI:

```text
http://localhost:8233
```

### Step 14: Run Quarkus

From the Quarkus project folder:

```bash
cd quarkus-app
./mvnw quarkus:dev
```

### Step 15: Test The API

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"abc-001","productCode":"KEYBOARD-001","quantity":1}'
```

Expected response:

```json
{
  "workflowId": "order-abc-001"
}
```

Then check Temporal Web UI:

```text
http://localhost:8233
```

You should see a workflow execution with ID:

```text
order-abc-001
```

## 8. How Many Main Steps Are Needed?

For a basic Temporal integration, remember these steps:

1. Add Temporal dependencies.
2. Add Temporal config properties.
3. Create activity interface.
4. Create activity implementation.
5. Create workflow interface.
6. Create workflow implementation.
7. Create worker startup code.
8. Inject `WorkflowClient`.
9. Start workflow from REST/API/application service.
10. Run Temporal Service and Quarkus app.
11. Check execution in Temporal Web UI.
12. Add tests and production configuration.

The most important mental model:

```text
REST API
    ↓ starts workflow
Temporal Client
    ↓ schedules workflow task
Temporal Service
    ↓ task queue
Worker
    ↓ runs workflow
Workflow
    ↓ calls activities
Activities
    ↓ external systems
Database / API / Email / Queue
```

## 9. Testing Temporal Code

Add:

```xml
<dependency>
    <groupId>io.temporal</groupId>
    <artifactId>temporal-testing</artifactId>
    <version>${temporal.version}</version>
    <scope>test</scope>
</dependency>
```

Temporal provides test utilities that let you run workflows without a real Temporal server.

Example style:

```java
package org.acme.temporal.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.acme.temporal.OrderTaskQueues;
import org.acme.temporal.activity.OrderActivitiesImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderWorkflowTest {

    private TestWorkflowEnvironment testEnvironment;

    @BeforeEach
    void setUp() {
        testEnvironment = TestWorkflowEnvironment.newInstance();

        Worker worker = testEnvironment.newWorker(OrderTaskQueues.ORDER_TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);
        worker.registerActivitiesImplementations(new OrderActivitiesImpl());

        testEnvironment.start();
    }

    @AfterEach
    void tearDown() {
        testEnvironment.close();
    }

    @Test
    void shouldCreateOrder() {
        WorkflowClient client = testEnvironment.getWorkflowClient();

        OrderWorkflow workflow = client.newWorkflowStub(
                OrderWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(OrderTaskQueues.ORDER_TASK_QUEUE)
                        .build()
        );

        String result = workflow.createOrder(
                new CreateOrderCommand("abc-001", "KEYBOARD-001", 1)
        );

        assertEquals("order-abc-001", result);
    }
}
```

## 10. Important Production Notes

### Separate API And Worker When Needed

For learning, it is fine to run the worker inside the Quarkus API service.

For production, consider separate deployments:

```text
order-api
order-worker
```

Benefits:

- Scale API and worker independently.
- Deploy worker changes carefully.
- Avoid API traffic affecting workflow execution.
- Control worker CPU and memory separately.

### Use Stable Workflow IDs

Use business idempotency keys:

```text
order-{idempotencyKey}
```

This helps prevent duplicate workflows when a client retries an HTTP request.

### Keep Workflow Code Deterministic

Do not call random APIs, system time, database, or HTTP directly in workflow code.

Put side effects in activities.

### Design Activities For Retry

Activities can run more than once if failures happen.

Good activity design:

- Use idempotency keys.
- Check whether the action already happened.
- Avoid duplicate payment charges.
- Avoid duplicate email if business rules require only one.

### Version Workflows Carefully

Workflow code can be replayed later. A running workflow may use old history with new code.

When changing workflow logic in production:

- Avoid breaking changes to workflow method signatures.
- Avoid removing steps that existing histories expect.
- Read Temporal versioning guidance before large workflow changes.

### Configure Timeouts

Always set activity timeouts.

Common timeout:

```java
ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(10))
        .build();
```

Without clear timeouts, failures can be harder to reason about.

## 11. Common Beginner Mistakes

Avoid these:

- Starting a workflow with one task queue but running a worker on another task queue.
- Forgetting to start the worker.
- Calling databases directly inside workflow implementation.
- Using `new Random()` or `System.currentTimeMillis()` inside workflow logic.
- Making activities non-idempotent.
- Returning `200 OK` for a process that only started but has not completed.
- Using random workflow IDs and accidentally creating duplicates.
- Forgetting Temporal Service must be running locally.
- Mixing business logic, REST code, and Temporal worker setup in one class.

## 12. Recommended Learning Order

For a new member, learn Temporal in this order:

1. Understand workflow, activity, worker, client, task queue.
2. Run `temporal server start-dev`.
3. Create one simple workflow and one activity.
4. Start the workflow from a Java main method.
5. Start the workflow from a Quarkus REST endpoint.
6. Add retry options and timeouts.
7. Add tests with `temporal-testing`.
8. Learn signals and queries.
9. Learn workflow versioning.
10. Learn production deployment patterns.

## 13. Quick Command Cheat Sheet

Start Temporal local development server:

```bash
temporal server start-dev
```

Open Temporal UI:

```text
http://localhost:8233
```

Run Quarkus dev mode:

```bash
cd quarkus-app
./mvnw quarkus:dev
```

Run tests:

```bash
cd quarkus-app
./mvnw test
```

Start order workflow:

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey":"abc-001","productCode":"KEYBOARD-001","quantity":1}'
```

## 14. Official References

Use official documentation when implementing real production workflows:

- Temporal Java SDK Developer Guide: https://docs.temporal.io/develop/java
- Temporal Java Quickstart: https://docs.temporal.io/develop/java/set-up-your-local-java
- Temporal Java Workflow Basics: https://docs.temporal.io/develop/java/workflows/basics
- Temporal Java Activity Basics: https://docs.temporal.io/develop/java/activities/basics
- Temporal Java Worker Processes: https://docs.temporal.io/develop/java/workers/run-worker-process
- Temporal Java Client: https://docs.temporal.io/develop/java/client/temporal-client
- Temporal Java SDK Maven Artifact: https://central.sonatype.com/artifact/io.temporal/temporal-sdk

