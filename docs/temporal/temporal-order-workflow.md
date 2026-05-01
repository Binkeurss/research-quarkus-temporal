# Temporal OrderWorkflow — Giải thích & Triển khai đầy đủ (Java Quarkus)

---

## 1. Thiết kế tổng thể & Lý do phân chia

```
OrderWorkflow (Parent - task queue: "order-queue")
│
├── InventoryWorkflow (Child - task queue: "inventory-queue")
│   └── [Activity] InventoryActivity  → gọi DB/API kiểm tra & giữ hàng
│
├── PaymentWorkflow   (Child - task queue: "payment-queue")
│   └── [Activity] PaymentActivity    → gọi Payment Gateway
│   └── [Signal]   confirmPayment / failPayment  ← từ webhook bên ngoài
│
├── ShippingWorkflow  (Child - task queue: "shipping-queue")
│   └── [Activity] ShippingActivity   → gọi Logistics API
│   └── [Signal]   updateShippingStatus ← từ courier callback
│
└── [Activity] NotificationActivity   → gửi email xác nhận đơn hàng
```

### Tại sao mỗi phần lại là Child Workflow, không phải Activity?

| | InventoryWorkflow | PaymentWorkflow | ShippingWorkflow | NotificationActivity |
|---|---|---|---|---|
| **Cần nhận Signal?** | ✅ Có (confirm/release) | ✅ Có (payment callback) | ✅ Có (courier update) | ❌ Không |
| **Long-running?** | ✅ Chờ payment xong | ✅ Vài phút (user thanh toán) | ✅ Vài ngày (giao hàng) | ❌ Vài giây |
| **Worker pool riêng?** | ✅ Inventory team | ✅ Payment team | ✅ Logistics team | ❌ Chung với Order |
| **Event History riêng?** | ✅ Cần | ✅ Cần | ✅ Cần | ❌ Không cần |
| **Có compensation?** | ✅ Release stock | ✅ Refund | ❌ | ❌ |

**Nguyên tắc quyết định:**
- Activity **KHÔNG THỂ** nhận Signal — nếu cần chờ callback từ bên ngoài → bắt buộc phải là Workflow
- Activity luôn bị cancel khi Parent cancel — nếu cần tiếp tục chạy độc lập → dùng Child Workflow + ABANDON
- Activity chỉ lưu input/output/retry trong Event History — nếu cần track state changes chi tiết → Child Workflow

---

## 2. Cấu trúc project

```
src/main/java/com/example/order/
│
├── config/
│   └── TemporalConfig.java           ← CDI Producer: WorkflowClient, WorkerFactory
│
├── worker/
│   └── TemporalWorkerStarter.java    ← Đăng ký workflows + activities, start workers
│
├── model/
│   ├── OrderRequest.java
│   ├── OrderResult.java
│   ├── PaymentRequest.java
│   ├── PaymentResult.java
│   ├── InventoryRequest.java
│   ├── InventoryResult.java
│   ├── ShippingRequest.java
│   └── ShippingResult.java
│
├── activity/
│   ├── NotificationActivity.java     ← interface
│   ├── NotificationActivityImpl.java
│   ├── PaymentActivity.java
│   ├── PaymentActivityImpl.java
│   ├── InventoryActivity.java
│   ├── InventoryActivityImpl.java
│   ├── ShippingActivity.java
│   └── ShippingActivityImpl.java
│
├── workflow/
│   ├── OrderWorkflow.java            ← interface (Parent)
│   ├── OrderWorkflowImpl.java
│   ├── PaymentWorkflow.java          ← interface (Child)
│   ├── PaymentWorkflowImpl.java
│   ├── InventoryWorkflow.java        ← interface (Child)
│   ├── InventoryWorkflowImpl.java
│   ├── ShippingWorkflow.java         ← interface (Child)
│   └── ShippingWorkflowImpl.java
│
└── api/
    └── OrderController.java          ← REST endpoint để trigger workflow
```

---

## 3. pom.xml

```xml
<?xml version="1.0"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.example</groupId>
  <artifactId>temporal-order</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <compiler-plugin.version>3.12.1</compiler-plugin.version>
    <quarkus.platform.version>3.9.4</quarkus.platform.version>
    <temporal.version>1.24.1</temporal.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>io.quarkus.platform</groupId>
        <artifactId>quarkus-bom</artifactId>
        <version>${quarkus.platform.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- Quarkus REST -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-rest-jackson</artifactId>
    </dependency>

    <!-- Quarkus CDI -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-arc</artifactId>
    </dependency>

    <!-- Temporal Java SDK -->
    <dependency>
      <groupId>io.temporal</groupId>
      <artifactId>temporal-sdk</artifactId>
      <version>${temporal.version}</version>
    </dependency>

    <!-- Lombok (optional, để giảm boilerplate) -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <version>1.18.32</version>
      <scope>provided</scope>
    </dependency>
  </dependencies>
</project>
```

---

## 4. Models

```java
// OrderRequest.java
package com.example.order.model;

public class OrderRequest {
    private String orderId;
    private String customerId;
    private String productId;
    private int quantity;
    private double amount;
    private String shippingAddress;
    // constructors, getters, setters
}

// OrderResult.java
public class OrderResult {
    private boolean success;
    private String orderId;
    private String message;
    private String transactionId;
    private String trackingNumber;

    public static OrderResult success(String orderId, String txId, String tracking) {
        OrderResult r = new OrderResult();
        r.success = true; r.orderId = orderId;
        r.transactionId = txId; r.trackingNumber = tracking;
        return r;
    }

    public static OrderResult failed(String reason) {
        OrderResult r = new OrderResult();
        r.success = false; r.message = reason;
        return r;
    }
}

// PaymentRequest.java
public class PaymentRequest {
    private String orderId;
    private String customerId;
    private double amount;
}

// PaymentResult.java
public class PaymentResult {
    private boolean success;
    private String transactionId;
    private String failReason;
}

// InventoryRequest.java
public class InventoryRequest {
    private String productId;
    private int quantity;
}

// InventoryResult.java
public class InventoryResult {
    private boolean success;    // true = đã reserve thành công
    private boolean released;   // true = đã release (compensation)
    private String message;

    public static InventoryResult reserved() {
        InventoryResult r = new InventoryResult();
        r.success = true; return r;
    }
    public static InventoryResult outOfStock() {
        InventoryResult r = new InventoryResult();
        r.success = false; r.message = "Out of stock"; return r;
    }
    public static InventoryResult released() {
        InventoryResult r = new InventoryResult();
        r.released = true; r.success = false; return r;
    }
}

// ShippingRequest.java
public class ShippingRequest {
    private String orderId;
    private String customerId;
    private String productId;
    private String shippingAddress;
}

// ShippingResult.java
public class ShippingResult {
    private boolean success;
    private String trackingNumber;
}
```

---

## 5. Activities (interfaces + implementations)

### 5.1 NotificationActivity

```java
// NotificationActivity.java
package com.example.order.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface NotificationActivity {

    @ActivityMethod
    void sendOrderConfirmation(String customerId, String orderId, String trackingNumber);

    @ActivityMethod
    void sendOrderFailureNotification(String customerId, String orderId, String reason);
}

// NotificationActivityImpl.java
public class NotificationActivityImpl implements NotificationActivity {

    @Override
    public void sendOrderConfirmation(String customerId, String orderId, String trackingNumber) {
        // Gọi Email/SMS service
        System.out.printf("[NOTIFICATION] Order %s confirmed for customer %s. Tracking: %s%n",
            orderId, customerId, trackingNumber);
        // emailService.send(...);
    }

    @Override
    public void sendOrderFailureNotification(String customerId, String orderId, String reason) {
        System.out.printf("[NOTIFICATION] Order %s failed for customer %s. Reason: %s%n",
            orderId, customerId, reason);
    }
}
```

### 5.2 InventoryActivity

```java
// InventoryActivity.java
@ActivityInterface
public interface InventoryActivity {

    @ActivityMethod
    boolean reserveStock(String productId, int quantity);

    @ActivityMethod
    void releaseStock(String productId, int quantity);

    @ActivityMethod
    void confirmReservation(String productId, int quantity);
}

// InventoryActivityImpl.java
public class InventoryActivityImpl implements InventoryActivity {

    @Override
    public boolean reserveStock(String productId, int quantity) {
        System.out.printf("[INVENTORY] Reserving %d units of product %s%n", quantity, productId);
        // inventoryRepository.reserve(productId, quantity);
        return true; // giả lập thành công
    }

    @Override
    public void releaseStock(String productId, int quantity) {
        System.out.printf("[INVENTORY] Releasing %d units of product %s%n", quantity, productId);
        // inventoryRepository.release(productId, quantity);
    }

    @Override
    public void confirmReservation(String productId, int quantity) {
        System.out.printf("[INVENTORY] Confirming reservation %d units of product %s%n",
            quantity, productId);
        // inventoryRepository.confirm(productId, quantity);
    }
}
```

### 5.3 PaymentActivity

```java
// PaymentActivity.java
@ActivityInterface
public interface PaymentActivity {

    @ActivityMethod
    String initiatePayment(String orderId, String customerId, double amount);
    // Trả về paymentSessionId để redirect user

    @ActivityMethod
    void refundPayment(String transactionId, double amount);
}

// PaymentActivityImpl.java
public class PaymentActivityImpl implements PaymentActivity {

    @Override
    public String initiatePayment(String orderId, String customerId, double amount) {
        System.out.printf("[PAYMENT] Initiating payment for order %s: $%.2f%n", orderId, amount);
        // Gọi Stripe/VNPay API để tạo payment session
        // return paymentGateway.createSession(orderId, amount);
        return "session-" + orderId; // giả lập
    }

    @Override
    public void refundPayment(String transactionId, double amount) {
        System.out.printf("[PAYMENT] Refunding transaction %s: $%.2f%n", transactionId, amount);
        // paymentGateway.refund(transactionId, amount);
    }
}
```

### 5.4 ShippingActivity

```java
// ShippingActivity.java
@ActivityInterface
public interface ShippingActivity {

    @ActivityMethod
    String createShipment(String orderId, String productId, String address);
    // Trả về trackingNumber

    @ActivityMethod
    String checkShipmentStatus(String trackingNumber);
}

// ShippingActivityImpl.java
public class ShippingActivityImpl implements ShippingActivity {

    @Override
    public String createShipment(String orderId, String productId, String address) {
        System.out.printf("[SHIPPING] Creating shipment for order %s to %s%n", orderId, address);
        // logisticsApi.createShipment(orderId, productId, address);
        return "TRACK-" + orderId; // giả lập tracking number
    }

    @Override
    public String checkShipmentStatus(String trackingNumber) {
        // logisticsApi.getStatus(trackingNumber);
        return "IN_TRANSIT"; // giả lập
    }
}
```

---

## 6. Workflow Interfaces

```java
// OrderWorkflow.java
package com.example.order.workflow;

import io.temporal.workflow.*;
import com.example.order.model.*;

@WorkflowInterface
public interface OrderWorkflow {

    @WorkflowMethod
    OrderResult processOrder(OrderRequest request);

    // Signal: user hoặc admin có thể cancel đơn hàng
    @SignalMethod
    void cancelOrder(String reason);

    // Query: frontend poll trạng thái đơn hàng real-time
    @QueryMethod
    String getOrderStatus();
}

// PaymentWorkflow.java
@WorkflowInterface
public interface PaymentWorkflow {

    @WorkflowMethod
    PaymentResult processPayment(PaymentRequest request);

    // Signal từ payment gateway webhook
    // Khi user hoàn tất thanh toán trên trang Stripe/VNPay
    @SignalMethod
    void confirmPayment(String transactionId);

    // Signal khi payment gateway báo thất bại
    @SignalMethod
    void failPayment(String reason);
}

// InventoryWorkflow.java
@WorkflowInterface
public interface InventoryWorkflow {

    @WorkflowMethod
    InventoryResult manageInventory(InventoryRequest request);

    // Signal từ OrderWorkflow: payment thành công → confirm inventory
    @SignalMethod
    void confirmInventory();

    // Signal từ OrderWorkflow: payment thất bại → release inventory
    @SignalMethod
    void releaseInventory();
}

// ShippingWorkflow.java
@WorkflowInterface
public interface ShippingWorkflow {

    @WorkflowMethod
    ShippingResult arrangeShipping(ShippingRequest request);

    // Signal từ logistics system khi trạng thái thay đổi
    @SignalMethod
    void updateShippingStatus(String status);
}
```

---

## 7. Workflow Implementations (PHẦN CỐT LÕI)

### 7.1 InventoryWorkflowImpl

```java
// InventoryWorkflowImpl.java
package com.example.order.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.*;
import com.example.order.activity.*;
import com.example.order.model.*;
import java.time.Duration;

/**
 * TẠI SAO InventoryWorkflow là Child Workflow (KHÔNG phải Activity)?
 *
 * 1. CẦN NHẬN SIGNAL:
 *    - Sau khi reserve, workflow CHỜ signal confirmInventory() hoặc releaseInventory()
 *    - Activity không thể nhận Signal — chỉ có Workflow mới nhận được
 *    - Nếu dùng Activity, ta không có cách nào để rollback (release) stock
 *      một cách đáng tin cậy sau khi payment thất bại
 *
 * 2. STATE MACHINE:
 *    PENDING → RESERVED → CONFIRMED
 *                       ↘ RELEASED (compensation)
 *    Activity chỉ là một function call đơn lẻ, không thể hold state như này
 *
 * 3. PARENT CLOSE POLICY = ABANDON:
 *    Dù Order workflow fail/timeout, inventory PHẢI hoàn tất việc release/confirm
 *    để không bị "treo" stock mãi mãi
 */
public class InventoryWorkflowImpl implements InventoryWorkflow {

    // State của workflow này — Temporal đảm bảo durability
    private String inventoryState = "PENDING";
    private boolean signalReceived = false;
    private boolean shouldConfirm = false;

    private final InventoryActivity inventoryActivity = Workflow.newActivityStub(
        InventoryActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build()
    );

    @Override
    public InventoryResult manageInventory(InventoryRequest request) {
        inventoryState = "ATTEMPTING_RESERVE";

        // Bước 1: Reserve stock qua Activity (đây là "external world" operation)
        boolean reserved = inventoryActivity.reserveStock(
            request.getProductId(),
            request.getQuantity()
        );

        if (!reserved) {
            inventoryState = "OUT_OF_STOCK";
            return InventoryResult.outOfStock();
        }

        inventoryState = "RESERVED";

        // Bước 2: ĐỢI signal từ Parent Workflow
        // Đây là lý do PHẢI là Child Workflow chứ không phải Activity!
        // Activity không thể Workflow.await() chờ Signal từ bên ngoài
        //
        // Timeout 30 phút: nếu không nhận được signal → tự động release
        boolean signalArrived = Workflow.await(
            Duration.ofMinutes(30),
            () -> signalReceived
        );

        if (!signalArrived || !shouldConfirm) {
            // Timeout hoặc nhận lệnh release → compensation
            inventoryState = "RELEASING";
            inventoryActivity.releaseStock(request.getProductId(), request.getQuantity());
            inventoryState = "RELEASED";
            return InventoryResult.released();
        }

        // Nhận được confirm → finalize
        inventoryState = "CONFIRMING";
        inventoryActivity.confirmReservation(request.getProductId(), request.getQuantity());
        inventoryState = "CONFIRMED";
        return InventoryResult.reserved();
    }

    @Override
    public void confirmInventory() {
        // Signal từ OrderWorkflow: payment thành công
        this.shouldConfirm = true;
        this.signalReceived = true;
    }

    @Override
    public void releaseInventory() {
        // Signal từ OrderWorkflow: payment thất bại
        this.shouldConfirm = false;
        this.signalReceived = true;
    }
}
```

### 7.2 PaymentWorkflowImpl

```java
// PaymentWorkflowImpl.java
/**
 * TẠI SAO PaymentWorkflow là Child Workflow (KHÔNG phải Activity)?
 *
 * 1. CẦN NHẬN SIGNAL từ bên ngoài:
 *    - User được redirect đến trang Stripe/VNPay
 *    - Có thể mất 1-30 phút để user hoàn tất
 *    - Khi xong, payment gateway gọi webhook → webhook gửi Signal vào workflow
 *    - Activity KHÔNG THỂ ngủ chờ webhook. Activity timeout sẽ retry lại,
 *      không phải chờ event bên ngoài
 *
 * 2. PARENT CLOSE POLICY = REQUEST_CANCEL:
 *    Nếu order bị đóng/timeout, ta muốn thử cancel payment để tránh charge nhầm
 *    Không dùng TERMINATE vì cần cleanup logic
 *
 * 3. SEPARATE WORKER POOL:
 *    Payment team quản lý task queue "payment-queue" riêng
 *    Có thể scale payment workers độc lập với order workers
 */
public class PaymentWorkflowImpl implements PaymentWorkflow {

    private String paymentState = "PENDING";
    private boolean paymentSignalReceived = false;
    private String transactionId;
    private String failReason;
    private boolean paymentSucceeded = false;

    private final PaymentActivity paymentActivity = Workflow.newActivityStub(
        PaymentActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .build()
    );

    @Override
    public PaymentResult processPayment(PaymentRequest request) {
        paymentState = "INITIATING";

        // Bước 1: Tạo payment session (Activity vì là external call đơn giản)
        String sessionId = paymentActivity.initiatePayment(
            request.getOrderId(),
            request.getCustomerId(),
            request.getAmount()
        );

        paymentState = "WAITING_USER";
        // Giả sử gửi email/notify cho user với payment link...

        // Bước 2: Chờ Signal từ webhook
        // Đây là lý do phải là Workflow!
        // Timeout 30 phút: user không thanh toán → fail
        boolean signalArrived = Workflow.await(
            Duration.ofMinutes(30),
            () -> paymentSignalReceived
        );

        if (!signalArrived) {
            paymentState = "TIMEOUT";
            PaymentResult result = new PaymentResult();
            result.setSuccess(false);
            result.setFailReason("Payment timeout after 30 minutes");
            return result;
        }

        if (!paymentSucceeded) {
            paymentState = "FAILED";
            PaymentResult result = new PaymentResult();
            result.setSuccess(false);
            result.setFailReason(this.failReason);
            return result;
        }

        paymentState = "COMPLETED";
        PaymentResult result = new PaymentResult();
        result.setSuccess(true);
        result.setTransactionId(this.transactionId);
        return result;
    }

    @Override
    public void confirmPayment(String transactionId) {
        // Signal gọi từ WebhookController khi payment gateway callback
        this.transactionId = transactionId;
        this.paymentSucceeded = true;
        this.paymentSignalReceived = true;
    }

    @Override
    public void failPayment(String reason) {
        // Signal gọi từ WebhookController khi payment thất bại
        this.failReason = reason;
        this.paymentSucceeded = false;
        this.paymentSignalReceived = true;
    }
}
```

### 7.3 ShippingWorkflowImpl

```java
// ShippingWorkflowImpl.java
/**
 * TẠI SAO ShippingWorkflow là Child Workflow (KHÔNG phải Activity)?
 *
 * 1. LONG-RUNNING (ngày → tuần):
 *    - Cần track: CREATED → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED
 *    - Activity timeout tối đa vài giờ, không thể chạy vài ngày
 *    - Workflow chạy vô thời hạn, durable
 *
 * 2. COMPLETELY SEPARATE SERVICE:
 *    - Team Logistics quản lý "shipping-queue" riêng
 *    - Có thể deploy, scale độc lập hoàn toàn với Order service
 *
 * 3. PARENT CLOSE POLICY = ABANDON:
 *    - Một khi hàng đã được giao cho courier, ORDER PHẢI TIẾP TỤC dù order workflow đóng
 *    - Đây là lý do rõ ràng nhất cần Child Workflow: Activity luôn bị cancel theo Parent
 *
 * 4. FIRE-AND-FORGET từ Parent:
 *    - OrderWorkflow KHÔNG await kết quả của ShippingWorkflow
 *    - Parent chỉ cần biết shipping đã được kick off
 *    - Shipping tự chạy đến khi delivered
 */
public class ShippingWorkflowImpl implements ShippingWorkflow {

    private String currentStatus = "CREATED";
    private boolean delivered = false;

    private final ShippingActivity shippingActivity = Workflow.newActivityStub(
        ShippingActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(60))
            .build()
    );

    @Override
    public ShippingResult arrangeShipping(ShippingRequest request) {
        currentStatus = "CREATING_SHIPMENT";

        // Tạo shipment với logistics provider
        String trackingNumber = shippingActivity.createShipment(
            request.getOrderId(),
            request.getProductId(),
            request.getShippingAddress()
        );

        currentStatus = "AWAITING_PICKUP";

        // Vòng lặp chờ delivered — có thể mất vài ngày
        // Workflow.await không tốn resources khi đang chờ
        while (!delivered) {
            // Chờ tối đa 1 ngày để nhận status update signal từ courier
            boolean updated = Workflow.await(Duration.ofDays(1), () -> delivered);

            if (!updated) {
                // Sau 1 ngày không có update → chủ động check
                String status = shippingActivity.checkShipmentStatus(trackingNumber);
                currentStatus = status;
                if ("DELIVERED".equals(status)) {
                    delivered = true;
                }
            }
        }

        currentStatus = "DELIVERED";
        ShippingResult result = new ShippingResult();
        result.setSuccess(true);
        result.setTrackingNumber(trackingNumber);
        return result;
    }

    @Override
    public void updateShippingStatus(String status) {
        // Signal từ logistics webhook
        this.currentStatus = status;
        if ("DELIVERED".equals(status)) {
            this.delivered = true;
        }
    }
}
```

### 7.4 OrderWorkflowImpl (PARENT - phần quan trọng nhất)

```java
// OrderWorkflowImpl.java
package com.example.order.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.workflow.*;
import com.example.order.activity.*;
import com.example.order.model.*;
import java.time.Duration;

/**
 * OrderWorkflow là PARENT WORKFLOW.
 * Điều phối toàn bộ luồng xử lý đơn hàng.
 *
 * QUAN TRỌNG: Các Child Workflow được tạo bằng Workflow.newChildWorkflowStub()
 * KHÔNG phải new InventoryWorkflowImpl() — Temporal quản lý vòng đời, không phải JVM
 */
public class OrderWorkflowImpl implements OrderWorkflow {

    // Track trạng thái để QueryMethod trả về cho frontend
    private String currentStatus = "PENDING";
    private boolean cancelled = false;
    private String cancelReason;

    // Activity dùng để gửi notification (chạy trên cùng worker với OrderWorkflow)
    private final NotificationActivity notificationActivity = Workflow.newActivityStub(
        NotificationActivity.class,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(10))
            .setRetryOptions(io.temporal.common.RetryOptions.newBuilder()
                .setMaximumAttempts(3)
                .build())
            .build()
    );

    @Override
    public OrderResult processOrder(OrderRequest request) {

        // Kiểm tra cancel signal trước khi bắt đầu
        if (cancelled) {
            return OrderResult.failed("Order cancelled before processing: " + cancelReason);
        }

        // ================================================================
        // BƯỚC 1: RESERVE INVENTORY (Child Workflow)
        // ================================================================
        currentStatus = "RESERVING_INVENTORY";

        /**
         * ChildWorkflowOptions quan trọng:
         *
         * setWorkflowId: Explicit ID cho phép ta gửi Signal về sau
         *   → "inventory-{orderId}" để dễ identify và signal
         *
         * setTaskQueue: "inventory-queue" → chạy trên Inventory Workers
         *   → Inventory team có thể scale/deploy riêng
         *
         * setParentClosePolicy(ABANDON):
         *   → Nếu OrderWorkflow đóng, InventoryWorkflow vẫn phải hoàn tất
         *     việc release/confirm để không bị "treo" stock
         *   → Với ABANDON, workflow con tiếp tục chạy → ta vẫn có thể gửi Signal
         */
        ChildWorkflowOptions inventoryOptions = ChildWorkflowOptions.newBuilder()
            .setWorkflowId("inventory-" + request.getOrderId())
            .setTaskQueue("inventory-queue")
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
            .build();

        InventoryWorkflow inventoryWorkflow = Workflow.newChildWorkflowStub(
            InventoryWorkflow.class,
            inventoryOptions
        );

        // Khởi chạy inventory workflow và await kết quả reserve
        // (chỉ await bước reserve, không phải toàn bộ lifecycle)
        InventoryResult inventoryResult = inventoryWorkflow.manageInventory(
            new InventoryRequest(request.getProductId(), request.getQuantity())
        );

        if (!inventoryResult.isSuccess()) {
            currentStatus = "FAILED";
            notificationActivity.sendOrderFailureNotification(
                request.getCustomerId(), request.getOrderId(), "Insufficient inventory");
            return OrderResult.failed("Insufficient inventory for product: " + request.getProductId());
        }

        // ================================================================
        // BƯỚC 2: PROCESS PAYMENT (Child Workflow)
        // ================================================================
        currentStatus = "PROCESSING_PAYMENT";

        /**
         * setParentClosePolicy(REQUEST_CANCEL):
         *   → Nếu OrderWorkflow bị đóng/timeout, gửi cancel request đến PaymentWorkflow
         *   → PaymentWorkflow có cơ hội dọn dẹp: cancel payment session với gateway
         *   → Không dùng TERMINATE vì cần cleanup, không dùng ABANDON vì không muốn
         *     tiếp tục charge tiền khi order đã đóng
         */
        ChildWorkflowOptions paymentOptions = ChildWorkflowOptions.newBuilder()
            .setWorkflowId("payment-" + request.getOrderId())
            .setTaskQueue("payment-queue")
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
            .setWorkflowExecutionTimeout(Duration.ofMinutes(35)) // payment window
            .build();

        PaymentWorkflow paymentWorkflow = Workflow.newChildWorkflowStub(
            PaymentWorkflow.class,
            paymentOptions
        );

        PaymentResult paymentResult = paymentWorkflow.processPayment(
            new PaymentRequest(request.getOrderId(), request.getCustomerId(), request.getAmount())
        );

        if (!paymentResult.isSuccess()) {
            // COMPENSATION: Payment thất bại → release inventory
            // Ta gửi Signal đến InventoryWorkflow (vẫn đang chạy do ABANDON policy)
            //
            // Tạo lại stub với workflow ID đã biết để gửi signal
            InventoryWorkflow inventorySignalStub = Workflow.newExternalWorkflowStub(
                InventoryWorkflow.class,
                "inventory-" + request.getOrderId()
            );
            inventorySignalStub.releaseInventory(); // Signal để release stock

            currentStatus = "FAILED";
            notificationActivity.sendOrderFailureNotification(
                request.getCustomerId(), request.getOrderId(), paymentResult.getFailReason());
            return OrderResult.failed("Payment failed: " + paymentResult.getFailReason());
        }

        // Payment thành công → confirm inventory reservation
        InventoryWorkflow inventoryConfirmStub = Workflow.newExternalWorkflowStub(
            InventoryWorkflow.class,
            "inventory-" + request.getOrderId()
        );
        inventoryConfirmStub.confirmInventory();

        // ================================================================
        // BƯỚC 3: ARRANGE SHIPPING (Child Workflow — FIRE AND FORGET)
        // ================================================================
        currentStatus = "ARRANGING_SHIPPING";

        /**
         * setParentClosePolicy(ABANDON):
         *   → Một khi hàng đã giao cho courier, KHÔNG BAO GIỜ cancel
         *   → ShippingWorkflow chạy tiếp dù OrderWorkflow kết thúc
         *
         * FIRE AND FORGET với Async.procedure():
         *   → OrderWorkflow không cần chờ delivery hoàn tất (có thể mất nhiều ngày)
         *   → Chỉ cần đảm bảo shipping workflow đã được khởi động
         */
        ChildWorkflowOptions shippingOptions = ChildWorkflowOptions.newBuilder()
            .setWorkflowId("shipping-" + request.getOrderId())
            .setTaskQueue("shipping-queue")
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
            .build();

        ShippingWorkflow shippingWorkflow = Workflow.newChildWorkflowStub(
            ShippingWorkflow.class,
            shippingOptions
        );

        // Kick off shipping KHÔNG await kết quả
        Promise<ShippingResult> shippingPromise = Async.function(
            shippingWorkflow::arrangeShipping,
            new ShippingRequest(
                request.getOrderId(),
                request.getCustomerId(),
                request.getProductId(),
                request.getShippingAddress()
            )
        );

        // Chờ đến khi shipping workflow ĐÃ ĐƯỢC SPAWN (không phải completed)
        // Điều này đảm bảo workflow đã tồn tại trước khi parent kết thúc
        Promise<WorkflowExecution> shippingExecution =
            Workflow.getChildWorkflowExecution(shippingWorkflow);
        shippingExecution.get(); // Blocking cho đến khi child được tạo

        // Tạo tracking number tạm thời
        String trackingNumber = "TRACK-" + request.getOrderId();

        // ================================================================
        // BƯỚC 4: SEND NOTIFICATION (Activity — KHÔNG phải Child Workflow)
        // ================================================================
        /**
         * TẠI SAO notification là Activity (không phải Child Workflow)?
         *   → Chỉ là 1 operation đơn giản: gửi email
         *   → Không cần nhận Signal
         *   → Không long-running
         *   → Không cần worker pool riêng
         *   → Activity retry 3 lần là đủ
         *   → Dùng Child Workflow ở đây là over-engineering
         */
        notificationActivity.sendOrderConfirmation(
            request.getCustomerId(),
            request.getOrderId(),
            trackingNumber
        );

        currentStatus = "COMPLETED";
        return OrderResult.success(
            request.getOrderId(),
            paymentResult.getTransactionId(),
            trackingNumber
        );
    }

    @Override
    public void cancelOrder(String reason) {
        // Signal có thể đến bất cứ lúc nào trong quá trình xử lý
        this.cancelled = true;
        this.cancelReason = reason;
    }

    @Override
    public String getOrderStatus() {
        // Query: luôn trả về ngay, không blocking
        return currentStatus;
    }
}
```

---

## 8. TemporalConfig — CDI Producer

```java
// TemporalConfig.java
package com.example.order.config;

import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * CDI Producer tạo WorkflowClient singleton.
 * WorkflowClient dùng để:
 * 1. Start workflow từ bên ngoài (REST API)
 * 2. Send signal đến workflow đang chạy (webhook)
 * 3. Query trạng thái workflow
 */
@ApplicationScoped
public class TemporalConfig {

    @ConfigProperty(name = "temporal.host", defaultValue = "localhost")
    String temporalHost;

    @ConfigProperty(name = "temporal.port", defaultValue = "7233")
    int temporalPort;

    @Produces
    @ApplicationScoped
    public WorkflowClient workflowClient() {
        WorkflowServiceStubs serviceStubs = WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(temporalHost + ":" + temporalPort)
                .build()
        );

        return WorkflowClient.newInstance(serviceStubs);
    }
}
```

---

## 9. TemporalWorkerStarter — Đăng ký và khởi động Workers

```java
// TemporalWorkerStarter.java
package com.example.order.worker;

import com.example.order.activity.*;
import com.example.order.workflow.*;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * QUAN TRỌNG: Hiểu rõ sự khác nhau giữa 4 task queues:
 *
 * "order-queue":
 *   → Xử lý OrderWorkflow (parent)
 *   → Cũng chứa NotificationActivity vì notification thuộc order domain
 *   → Team Order sở hữu queue này
 *
 * "inventory-queue":
 *   → Xử lý InventoryWorkflow + InventoryActivity
 *   → Team Inventory/Warehouse sở hữu queue này
 *   → Có thể deploy và scale hoàn toàn độc lập
 *   → Trong production: đây có thể là một microservice riêng biệt
 *
 * "payment-queue":
 *   → Xử lý PaymentWorkflow + PaymentActivity
 *   → Team Payment sở hữu queue này
 *   → PCI-DSS compliance: isolated environment cho payment processing
 *
 * "shipping-queue":
 *   → Xử lý ShippingWorkflow + ShippingActivity
 *   → Team Logistics sở hữu queue này
 *   → Worker này có thể chạy ở datacenter gần logistics partner
 *
 * Trong demo này tất cả chạy trong 1 JVM,
 * nhưng trong production mỗi queue có thể là một service riêng.
 */
@ApplicationScoped
public class TemporalWorkerStarter {

    @Inject
    WorkflowClient workflowClient;

    void onStart(@Observes StartupEvent event) {
        WorkerFactory factory = WorkerFactory.newInstance(workflowClient);

        registerOrderWorker(factory);
        registerInventoryWorker(factory);
        registerPaymentWorker(factory);
        registerShippingWorker(factory);

        // Start tất cả workers
        factory.start();

        System.out.println("[TEMPORAL] All workers started successfully");
    }

    private void registerOrderWorker(WorkerFactory factory) {
        Worker worker = factory.newWorker("order-queue");

        // Đăng ký workflow implementations
        worker.registerWorkflowImplementationTypes(OrderWorkflowImpl.class);

        // Đăng ký activity implementations
        // NotificationActivity chạy cùng worker vì cùng domain
        worker.registerActivitiesImplementations(new NotificationActivityImpl());

        System.out.println("[TEMPORAL] Order worker registered");
    }

    private void registerInventoryWorker(WorkerFactory factory) {
        Worker worker = factory.newWorker("inventory-queue");

        worker.registerWorkflowImplementationTypes(InventoryWorkflowImpl.class);
        worker.registerActivitiesImplementations(new InventoryActivityImpl());

        System.out.println("[TEMPORAL] Inventory worker registered");
    }

    private void registerPaymentWorker(WorkerFactory factory) {
        Worker worker = factory.newWorker("payment-queue");

        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        worker.registerActivitiesImplementations(new PaymentActivityImpl());

        System.out.println("[TEMPORAL] Payment worker registered");
    }

    private void registerShippingWorker(WorkerFactory factory) {
        Worker worker = factory.newWorker("shipping-queue");

        worker.registerWorkflowImplementationTypes(ShippingWorkflowImpl.class);
        worker.registerActivitiesImplementations(new ShippingActivityImpl());

        System.out.println("[TEMPORAL] Shipping worker registered");
    }
}
```

---

## 10. REST API — Trigger và tương tác với Workflow

```java
// OrderController.java
package com.example.order.api;

import com.example.order.model.OrderRequest;
import com.example.order.model.OrderResult;
import com.example.order.workflow.OrderWorkflow;
import com.example.order.workflow.PaymentWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;

@Path("/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderController {

    @Inject
    WorkflowClient workflowClient;

    /**
     * POST /orders — Tạo đơn hàng mới, khởi chạy OrderWorkflow
     */
    @POST
    public Response createOrder(OrderRequest request) {
        WorkflowOptions options = WorkflowOptions.newBuilder()
            .setWorkflowId("order-" + request.getOrderId())
            .setTaskQueue("order-queue")
            .setWorkflowExecutionTimeout(Duration.ofHours(1))
            .build();

        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class, options);

        // Start workflow ASYNC — không chờ kết quả
        WorkflowClient.start(workflow::processOrder, request);

        return Response.accepted()
            .entity("{\"orderId\": \"" + request.getOrderId() + "\", \"status\": \"PROCESSING\"}")
            .build();
    }

    /**
     * GET /orders/{orderId}/status — Query trạng thái workflow real-time
     * Query KHÔNG cần workflow đang active, có thể query lịch sử
     */
    @GET
    @Path("/{orderId}/status")
    public Response getOrderStatus(@PathParam("orderId") String orderId) {
        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            "order-" + orderId  // Kết nối đến workflow đang chạy bằng ID
        );

        String status = workflow.getOrderStatus(); // QueryMethod — non-blocking
        return Response.ok("{\"status\": \"" + status + "\"}").build();
    }

    /**
     * DELETE /orders/{orderId} — Cancel đơn hàng qua Signal
     */
    @DELETE
    @Path("/{orderId}")
    public Response cancelOrder(
        @PathParam("orderId") String orderId,
        @QueryParam("reason") String reason) {

        OrderWorkflow workflow = workflowClient.newWorkflowStub(
            OrderWorkflow.class,
            "order-" + orderId
        );

        workflow.cancelOrder(reason); // SignalMethod — async, non-blocking
        return Response.ok("{\"message\": \"Cancel signal sent\"}").build();
    }
}

// WebhookController.java — Nhận callbacks từ external services
@Path("/webhooks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebhookController {

    @Inject
    WorkflowClient workflowClient;

    /**
     * POST /webhooks/payment/confirm
     * Payment gateway gọi endpoint này khi user hoàn tất thanh toán
     */
    @POST
    @Path("/payment/confirm")
    public Response confirmPayment(
        @QueryParam("orderId") String orderId,
        @QueryParam("transactionId") String transactionId) {

        // Gửi Signal đến PaymentWorkflow đang chờ
        // Workflow ID của PaymentWorkflow = "payment-{orderId}"
        PaymentWorkflow paymentWorkflow = workflowClient.newWorkflowStub(
            PaymentWorkflow.class,
            "payment-" + orderId
        );

        paymentWorkflow.confirmPayment(transactionId); // SignalMethod
        return Response.ok().build();
    }

    /**
     * POST /webhooks/payment/fail
     * Payment gateway gọi khi payment thất bại
     */
    @POST
    @Path("/payment/fail")
    public Response failPayment(
        @QueryParam("orderId") String orderId,
        @QueryParam("reason") String reason) {

        PaymentWorkflow paymentWorkflow = workflowClient.newWorkflowStub(
            PaymentWorkflow.class,
            "payment-" + orderId
        );

        paymentWorkflow.failPayment(reason); // SignalMethod
        return Response.ok().build();
    }
}
```

---

## 11. application.properties

```properties
# Temporal server
temporal.host=localhost
temporal.port=7233

# Quarkus
quarkus.http.port=8080
quarkus.log.level=INFO
quarkus.log.category."io.temporal".level=DEBUG
```

---

## 12. Tóm tắt luồng thực thi

```
POST /orders
    │
    ▼
OrderWorkflow.processOrder()
    │
    ├─[1]─► InventoryWorkflow.manageInventory()     [task: inventory-queue]
    │           │
    │           ├── [Activity] reserveStock()
    │           └── Workflow.await(signal)  ← CHỜ ở đây
    │                   ↑
    │    (sau khi payment xong, parent gửi signal)
    │
    ├─[2]─► PaymentWorkflow.processPayment()        [task: payment-queue]
    │           │
    │           ├── [Activity] initiatePayment()
    │           └── Workflow.await(signal)  ← CHỜ webhook từ payment gateway
    │                   ↑
    │    POST /webhooks/payment/confirm  ← từ Stripe/VNPay
    │
    │   [Nếu payment thất bại]
    │       └── Signal → InventoryWorkflow.releaseInventory()
    │
    │   [Nếu payment thành công]
    │       └── Signal → InventoryWorkflow.confirmInventory()
    │
    ├─[3]─► ShippingWorkflow.arrangeShipping()      [task: shipping-queue]
    │           │  (FIRE AND FORGET — parent không await)
    │           ├── [Activity] createShipment()
    │           └── while(!delivered) { await(signal) } ← chờ nhiều ngày
    │                   ↑
    │    POST /webhooks/shipping/update ← từ logistics system
    │
    └─[4]─► [Activity] NotificationActivity.sendOrderConfirmation()
```

---

## 13. Docker Compose để chạy local

```yaml
# docker-compose.yml
version: '3.8'
services:
  temporal:
    image: temporalio/auto-setup:1.24.0
    ports:
      - "7233:7233"
    environment:
      - DB=sqlite

  temporal-ui:
    image: temporalio/ui:2.26.0
    ports:
      - "8088:8080"
    environment:
      - TEMPORAL_ADDRESS=temporal:7233
    depends_on:
      - temporal
```

```bash
# Chạy Temporal server
docker-compose up -d

# Chạy Quarkus app
./mvnw quarkus:dev

# Test tạo order
curl -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "ORD-001",
    "customerId": "CUST-001",
    "productId": "PROD-001",
    "quantity": 2,
    "amount": 150.00,
    "shippingAddress": "123 Main St, HCM"
  }'

# Check status
curl http://localhost:8080/orders/ORD-001/status

# Simulate payment webhook
curl -X POST "http://localhost:8080/webhooks/payment/confirm?orderId=ORD-001&transactionId=TX-12345"

# Xem Temporal UI tại http://localhost:8088
```
