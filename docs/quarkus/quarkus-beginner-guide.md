# Quarkus Beginner Guide

This document is for new backend members who are learning Quarkus for cloud-native Java services. It explains what Quarkus is, why it exists, which problems it solves, the important syntax and annotations, and how to create a clean standard project structure.

## 1. What Is Quarkus?

Quarkus is a modern Java framework designed for cloud, container, Kubernetes, and serverless workloads.

Traditional Java frameworks are powerful, but they were created when applications usually ran as large servers or long-running virtual machines. Cloud-native systems changed the expectations:

- Applications should start fast.
- Containers should be small.
- Memory usage should be low.
- Developers should get quick feedback while coding.
- Applications should work well in Kubernetes and CI/CD pipelines.

Quarkus was created to make Java fit those expectations.

In simple words:

> Quarkus helps Java applications behave like lightweight cloud-native services while keeping the Java ecosystem, libraries, and developer experience.

## 2. Why Was Quarkus Created?

Quarkus was created because many Java applications had problems in modern cloud environments.

### Problem 1: Slow Startup

In Kubernetes, serverless, autoscaling, and container platforms, services may start and stop often. If an application needs many seconds to start, scaling becomes slow.

Quarkus solves this by doing more work at build time instead of runtime. This means the application has less work to do when it starts.

### Problem 2: High Memory Usage

Containers usually have limited CPU and memory. A framework that uses too much memory increases infrastructure cost.

Quarkus reduces runtime overhead by optimizing dependency injection, configuration, reflection, and extension behavior during build.

### Problem 3: Java Was Not Always Container Friendly

Older Java application styles were often built for application servers, not small container images.

Quarkus supports container-first packaging, health checks, metrics, Kubernetes configuration, OpenAPI, reactive programming, and native executable builds.

### Problem 4: Slow Development Feedback

Restarting a backend service after every code change wastes time.

Quarkus provides dev mode:

```bash
./mvnw quarkus:dev
```

In dev mode, Quarkus automatically reloads code and configuration changes. This is one of the best features for day-to-day development.

## 3. What Problems Does Quarkus Solve?

Quarkus is useful when you need to build:

- REST APIs
- Microservices
- Event-driven services
- Kubernetes workloads
- Serverless functions
- Applications that connect to databases, queues, caches, and external APIs
- Java services that need fast startup and lower memory usage

Important benefits:

- Fast startup time
- Lower memory consumption
- Excellent developer experience with live reload
- Strong Kubernetes and container support
- Build-time optimization
- Easy extension system
- Native executable support with GraalVM or Mandrel
- Works with familiar Java standards such as Jakarta REST, CDI, JPA, Bean Validation, and MicroProfile

## 4. Core Quarkus Concepts

### 4.1 Extensions

Quarkus features are added through extensions.

Examples:

- `rest` for REST APIs
- `rest-jackson` for JSON serialization
- `hibernate-orm-panache` for database access
- `jdbc-postgresql` for PostgreSQL
- `hibernate-validator` for request validation
- `smallrye-openapi` for OpenAPI documentation
- `smallrye-health` for health checks

Add an extension with Maven:

```bash
./mvnw quarkus:add-extension -Dextensions="rest,rest-jackson"
```

Or create a project with extensions:

```bash
mvn io.quarkus.platform:quarkus-maven-plugin:create \
  -DprojectGroupId=com.example \
  -DprojectArtifactId=order-service \
  -Dextensions="rest,rest-jackson,hibernate-validator"
```

The exact plugin version is normally managed by the Quarkus platform when the project is generated.

### 4.2 Build-Time Optimization

Quarkus tries to move framework setup from runtime to build time.

For example, instead of discovering everything when the app starts, Quarkus analyzes many things during the build:

- Dependency injection beans
- REST endpoints
- Configuration metadata
- Persistence setup
- Reflection needs

That is why Quarkus can start quickly.

### 4.3 Dev Mode

Dev mode runs the app with live reload:

```bash
./mvnw quarkus:dev
```

Use it while developing. Do not use dev mode in production.

Common dev mode features:

- Automatic reload after source changes
- Dev UI
- Continuous testing
- Easy configuration testing
- Fast feedback loop

### 4.4 Configuration

Quarkus configuration usually lives here:

```text
src/main/resources/application.properties
```

Example:

```properties
quarkus.http.port=8080
quarkus.log.level=INFO

app.greeting=Hello from Quarkus
```

Read custom configuration with `@ConfigProperty`:

```java
package com.example.order.config;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class GreetingConfig {

    @ConfigProperty(name = "app.greeting")
    String greeting;

    public String greeting() {
        return greeting;
    }
}
```

Quarkus also supports profiles:

```properties
%dev.quarkus.http.port=8080
%test.quarkus.http.port=8081
%prod.quarkus.http.port=8080
```

### 4.5 Dependency Injection

Quarkus uses CDI, the standard Java dependency injection model.

Common annotations:

- `@ApplicationScoped`: one shared bean for the application
- `@RequestScoped`: one bean instance per request
- `@Inject`: inject another bean
- `@Produces`: create a bean manually

Example service:

```java
package com.example.order.service;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class OrderService {

    public String createOrder(String productCode) {
        return "Order created for product " + productCode;
    }
}
```

Inject it into a REST resource:

```java
package com.example.order.api;

import com.example.order.service.OrderService;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("/orders")
public class OrderResource {

    @Inject
    OrderService orderService;

    @POST
    public String create(String productCode) {
        return orderService.createOrder(productCode);
    }
}
```

Constructor injection is also common and easier to test:

```java
package com.example.order.api;

import com.example.order.service.OrderService;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("/orders")
public class OrderResource {

    private final OrderService orderService;

    public OrderResource(OrderService orderService) {
        this.orderService = orderService;
    }

    @POST
    public String create(String productCode) {
        return orderService.createOrder(productCode);
    }
}
```

## 5. Important Quarkus Syntax And Annotations

### 5.1 REST API Syntax

Quarkus REST APIs use Jakarta REST annotations.

Common annotations:

- `@Path`: defines URL path
- `@GET`: HTTP GET method
- `@POST`: HTTP POST method
- `@PUT`: HTTP PUT method
- `@DELETE`: HTTP DELETE method
- `@PathParam`: reads path parameter
- `@QueryParam`: reads query parameter
- `@Consumes`: request body media type
- `@Produces`: response media type

Example:

```java
package com.example.order.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/products")
@Produces(MediaType.APPLICATION_JSON)
public class ProductResource {

    @GET
    @Path("/{id}")
    public ProductResponse findById(@PathParam("id") Long id) {
        return new ProductResponse(id, "Keyboard");
    }
}
```

DTO:

```java
package com.example.order.api;

public record ProductResponse(Long id, String name) {
}
```

### 5.2 Request Body Syntax

```java
package com.example.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

@Path("/orders")
public class OrderResource {

    @POST
    public OrderResponse create(@Valid CreateOrderRequest request) {
        return new OrderResponse(1L, request.productCode());
    }

    public record CreateOrderRequest(
            @NotBlank String productCode
    ) {
    }

    public record OrderResponse(
            Long id,
            String productCode
    ) {
    }
}
```

The `@Valid` annotation tells Quarkus to validate the request object. The `@NotBlank` annotation rejects empty values.

### 5.3 Response Status

```java
package com.example.order.api;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/orders")
public class OrderResource {

    @POST
    public Response create(CreateOrderRequest request) {
        OrderResponse response = new OrderResponse(1L, request.productCode());
        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    public record CreateOrderRequest(String productCode) {
    }

    public record OrderResponse(Long id, String productCode) {
    }
}
```

### 5.4 Exception Handling

Create a mapper for custom exceptions:

```java
package com.example.order.api;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<NotFoundException> {

    @Override
    public Response toResponse(NotFoundException exception) {
        ErrorResponse error = new ErrorResponse("NOT_FOUND", exception.getMessage());
        return Response.status(Response.Status.NOT_FOUND).entity(error).build();
    }

    public record ErrorResponse(String code, String message) {
    }
}
```

## 6. Standard Quarkus Folder Structure

A generated Maven Quarkus project normally looks like this:

```text
order-service/
├── .mvn/
│   └── wrapper/
├── mvnw
├── mvnw.cmd
├── pom.xml
├── README.md
└── src/
    ├── main/
    │   ├── docker/
    │   │   ├── Dockerfile.jvm
    │   │   ├── Dockerfile.native
    │   │   └── Dockerfile.native-micro
    │   ├── java/
    │   │   └── com/
    │   │       └── example/
    │   │           └── order/
    │   │               ├── api/
    │   │               ├── application/
    │   │               ├── domain/
    │   │               ├── infrastructure/
    │   │               └── config/
    │   └── resources/
    │       └── application.properties
    └── test/
        └── java/
            └── com/
                └── example/
                    └── order/
```

### Recommended Package Structure

For a professional backend service, use package names that explain responsibility.

```text
com.example.order
├── api
├── application
├── domain
├── infrastructure
└── config
```

### `api`

Contains HTTP resources, request DTOs, response DTOs, and exception mappers.

Example classes:

```text
OrderResource.java
CreateOrderRequest.java
OrderResponse.java
GlobalExceptionMapper.java
```

Responsibility:

- Receive HTTP requests
- Validate input
- Convert request data into application commands
- Return HTTP responses

Avoid putting business logic here.

### `application`

Contains use cases and orchestration logic.

Example classes:

```text
CreateOrderUseCase.java
CancelOrderUseCase.java
OrderApplicationService.java
```

Responsibility:

- Coordinate business flow
- Call domain objects
- Call repositories or external gateways
- Manage transaction boundaries when needed

### `domain`

Contains core business concepts.

Example classes:

```text
Order.java
OrderStatus.java
OrderRepository.java
OrderPolicy.java
```

Responsibility:

- Business rules
- Domain models
- Domain interfaces
- Validation that belongs to the business, not only HTTP input

The domain package should avoid depending on HTTP, database, or framework details when possible.

### `infrastructure`

Contains technical implementations.

Example classes:

```text
JpaOrderRepository.java
PaymentClient.java
KafkaOrderEventPublisher.java
```

Responsibility:

- Database access
- External API clients
- Message brokers
- File storage
- Cache clients

### `config`

Contains application configuration classes and producers.

Example classes:

```text
ObjectMapperConfig.java
RestClientConfig.java
ClockProducer.java
```

Responsibility:

- Framework configuration
- Bean producers
- App-level configuration mapping

## 7. Step-by-Step: Create A New Quarkus Project

### Step 1: Install Requirements

Minimum tools:

- JDK 17 or newer
- Maven 3.9 or newer
- IDE such as IntelliJ IDEA, VS Code, or Eclipse

Check Java:

```bash
java -version
```

Check Maven:

```bash
mvn --version
```

### Step 2: Create Project

Using Maven:

```bash
mvn io.quarkus.platform:quarkus-maven-plugin:create \
  -DprojectGroupId=com.example \
  -DprojectArtifactId=order-service \
  -DclassName="com.example.order.api.GreetingResource" \
  -Dpath="/hello" \
  -Dextensions="rest,rest-jackson,hibernate-validator,smallrye-openapi,smallrye-health"
```

Go into the project:

```bash
cd order-service
```

### Step 3: Run In Dev Mode

```bash
./mvnw quarkus:dev
```

Open:

```text
http://localhost:8080/hello
```

Quarkus also provides a Dev UI in development mode:

```text
http://localhost:8080/q/dev-ui
```

### Step 4: Add Configuration

Edit:

```text
src/main/resources/application.properties
```

Example:

```properties
quarkus.http.port=8080
quarkus.application.name=order-service

%dev.quarkus.log.level=DEBUG
%prod.quarkus.log.level=INFO
```

### Step 5: Add A Resource

Create:

```text
src/main/java/com/example/order/api/OrderResource.java
```

```java
package com.example.order.api;

import com.example.order.application.CreateOrderUseCase;

import jakarta.validation.Valid;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/orders")
public class OrderResource {

    private final CreateOrderUseCase createOrderUseCase;

    public OrderResource(CreateOrderUseCase createOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
    }

    @POST
    public Response create(@Valid CreateOrderRequest request) {
        OrderResponse response = createOrderUseCase.create(request);
        return Response.status(Response.Status.CREATED).entity(response).build();
    }
}
```

Create request and response records:

```java
package com.example.order.api;

import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(
        @NotBlank String productCode
) {
}
```

```java
package com.example.order.api;

public record OrderResponse(
        Long id,
        String productCode,
        String status
) {
}
```

### Step 6: Add Application Logic

Create:

```text
src/main/java/com/example/order/application/CreateOrderUseCase.java
```

```java
package com.example.order.application;

import com.example.order.api.CreateOrderRequest;
import com.example.order.api.OrderResponse;
import com.example.order.domain.Order;
import com.example.order.domain.OrderRepository;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CreateOrderUseCase {

    private final OrderRepository orderRepository;

    public CreateOrderUseCase(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public OrderResponse create(CreateOrderRequest request) {
        Order order = Order.create(request.productCode());
        Order savedOrder = orderRepository.save(order);
        return new OrderResponse(savedOrder.id(), savedOrder.productCode(), savedOrder.status());
    }
}
```

### Step 7: Add Domain Model

Create:

```text
src/main/java/com/example/order/domain/Order.java
```

```java
package com.example.order.domain;

public record Order(
        Long id,
        String productCode,
        String status
) {

    public static Order create(String productCode) {
        return new Order(null, productCode, "CREATED");
    }
}
```

Create repository interface:

```java
package com.example.order.domain;

public interface OrderRepository {

    Order save(Order order);
}
```

### Step 8: Add Infrastructure Implementation

For a beginner example, use an in-memory repository:

```java
package com.example.order.infrastructure;

import com.example.order.domain.Order;
import com.example.order.domain.OrderRepository;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class InMemoryOrderRepository implements OrderRepository {

    private final AtomicLong sequence = new AtomicLong();
    private final Map<Long, Order> orders = new ConcurrentHashMap<>();

    @Override
    public Order save(Order order) {
        long id = sequence.incrementAndGet();
        Order savedOrder = new Order(id, order.productCode(), order.status());
        orders.put(id, savedOrder);
        return savedOrder;
    }
}
```

Later, replace this with PostgreSQL, Hibernate ORM, Panache, or another persistence technology.

### Step 9: Test The API

Use `curl`:

```bash
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{"productCode":"KEYBOARD-001"}'
```

Expected response:

```json
{
  "id": 1,
  "productCode": "KEYBOARD-001",
  "status": "CREATED"
}
```

### Step 10: Build The Application

```bash
./mvnw package
```

Run the packaged application:

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

## 8. Testing In Quarkus

Quarkus supports tests with `@QuarkusTest`.

Example:

```java
package com.example.order.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class OrderResourceTest {

    @Test
    void shouldCreateOrder() {
        given()
                .contentType("application/json")
                .body("{\"productCode\":\"KEYBOARD-001\"}")
                .when()
                .post("/orders")
                .then()
                .statusCode(201)
                .body("productCode", equalTo("KEYBOARD-001"))
                .body("status", equalTo("CREATED"));
    }
}
```

Run tests:

```bash
./mvnw test
```

## 9. Common Dependencies For Backend Services

REST API:

```bash
./mvnw quarkus:add-extension -Dextensions="rest,rest-jackson"
```

Validation:

```bash
./mvnw quarkus:add-extension -Dextensions="hibernate-validator"
```

OpenAPI:

```bash
./mvnw quarkus:add-extension -Dextensions="smallrye-openapi"
```

Health checks:

```bash
./mvnw quarkus:add-extension -Dextensions="smallrye-health"
```

PostgreSQL with Hibernate ORM and Panache:

```bash
./mvnw quarkus:add-extension -Dextensions="hibernate-orm-panache,jdbc-postgresql"
```

REST Client:

```bash
./mvnw quarkus:add-extension -Dextensions="rest-client,rest-client-jackson"
```

## 10. Cloud And Production Concerns

### Health Checks

Health checks help Kubernetes know if the application is alive and ready.

Add:

```bash
./mvnw quarkus:add-extension -Dextensions="smallrye-health"
```

Common endpoints:

```text
/q/health
/q/health/live
/q/health/ready
```

### OpenAPI

OpenAPI helps frontend and backend teams understand the API contract.

Add:

```bash
./mvnw quarkus:add-extension -Dextensions="smallrye-openapi"
```

Common endpoint:

```text
/q/openapi
```

Swagger UI is usually available in dev mode:

```text
/q/swagger-ui
```

### Configuration Through Environment Variables

Most Quarkus properties can be configured by environment variables.

Example property:

```properties
quarkus.http.port=8080
```

Equivalent environment variable:

```bash
QUARKUS_HTTP_PORT=8080
```

This is useful in Docker, Kubernetes, and CI/CD.

### Container Image

Quarkus generated projects include Dockerfiles under:

```text
src/main/docker/
```

Typical JVM container build flow:

```bash
./mvnw package
docker build -f src/main/docker/Dockerfile.jvm -t order-service:1.0.0 .
docker run -i --rm -p 8080:8080 order-service:1.0.0
```

## 11. Best Practices For New Members

Use these rules when starting with Quarkus:

- Keep REST resources thin.
- Put business flow in application services or use cases.
- Put business rules in domain classes.
- Put database, messaging, and external APIs in infrastructure classes.
- Use DTOs for API request and response objects.
- Validate inputs with `jakarta.validation`.
- Use constructor injection for easier testing.
- Use `application.properties` for configuration.
- Use profiles for environment-specific values.
- Do not hardcode secrets.
- Add health checks for production services.
- Add OpenAPI for APIs shared with other teams.
- Write tests for important API behavior.
- Prefer Quarkus extensions over manually wiring third-party libraries.

## 12. Beginner Mental Model

Think about a Quarkus backend in layers:

```text
HTTP request
    ↓
api package
    ↓
application package
    ↓
domain package
    ↓
infrastructure package
    ↓
database, queue, external service
```

Each layer has a clear job:

- `api`: talks HTTP
- `application`: coordinates use cases
- `domain`: protects business rules
- `infrastructure`: talks to technology
- `config`: wires application settings

This structure keeps the code easier to understand, test, and change.

## 13. Quick Command Cheat Sheet

Create project:

```bash
mvn io.quarkus.platform:quarkus-maven-plugin:create \
  -DprojectGroupId=com.example \
  -DprojectArtifactId=order-service \
  -Dextensions="rest,rest-jackson"
```

Run dev mode:

```bash
./mvnw quarkus:dev
```

Add extension:

```bash
./mvnw quarkus:add-extension -Dextensions="hibernate-validator"
```

Run tests:

```bash
./mvnw test
```

Build:

```bash
./mvnw package
```

Run packaged app:

```bash
java -jar target/quarkus-app/quarkus-run.jar
```

## 14. Official References

Use the official Quarkus documentation when you need exact version-specific details:

- Quarkus Getting Started: https://quarkus.io/guides/getting-started
- Quarkus Maven Tooling: https://quarkus.io/guides/maven-tooling
- Quarkus CLI Tooling: https://quarkus.io/guides/cli-tooling
- Quarkus Extension Catalog: https://quarkus.io/extensions/

