# Hướng dẫn cơ bản về Helm Chart và Phân tích etcd-manager

Tài liệu này được tạo ra để giúp bạn (một người mới tiếp cận Kubernetes và Helm) hiểu được các khái niệm cơ bản về Helm, cũng như cách thức hoạt động của Helm chart `etcd-manager` cụ thể trong dự án của bạn.

## 1. Helm và Helm Chart là gì?

**Helm** được ví như "trình quản lý gói" (Package Manager) cho Kubernetes (tương tự như `apt` cho Ubuntu hay `npm` cho Node.js). 
Thay vì phải viết và quản lý hàng chục file YAML cấu hình Kubernetes (Deployment, Service, ConfigMap, v.v.) một cách thủ công, Helm cho phép bạn đóng gói chúng lại thành một **Helm Chart**.

Một **Helm Chart** là một tập hợp các file template (khuôn mẫu). Khi bạn muốn triển khai ứng dụng, bạn chỉ cần cung cấp các thông số đầu vào, Helm sẽ tự động "fill" (điền) các thông số đó vào template và tạo ra các file YAML hoàn chỉnh để gửi lên cụm Kubernetes.

### Cấu trúc tiêu chuẩn của một Helm Chart
- `Chart.yaml`: Chứa thông tin mô tả về chart (tên chart, phiên bản, mô tả ứng dụng, tác giả...).
- `values.yaml`: Chứa các giá trị mặc định (default values). Người dùng có thể ghi đè (override) các giá trị này khi cài đặt.
- `templates/`: Thư mục chứa các file template. Helm sẽ sử dụng các giá trị từ `values.yaml` kết hợp với template ở đây để sinh ra các file Kubernetes manifest cuối cùng.
- `charts/`: (Tuỳ chọn) Chứa các chart phụ thuộc (sub-charts).

---

## 2. Phân tích cấu trúc thư mục `etcd-manager`

Thư mục `/vks-helm-charts/charts/etcd-manager` là một Helm chart dùng để **quản lý vòng đời chứng chỉ (certificate) và theo dõi sức khoẻ (health monitoring) cho các cluster ETCD** trong môi trường Kamaji.

### Vai trò của các file chính trong thư mục:

1. **`Chart.yaml`**: Định nghĩa biểu đồ (chart) này tên là `etcd-manager`, phiên bản `1.0.0`, được thiết kế để theo dõi vòng đời chứng chỉ của ETCD.
2. **`values.yaml`**: Chứa toàn bộ cấu hình mặc định của chart có thể tùy chỉnh. Ví dụ:
   - `namespace: datastore`: Tên namespace cài đặt mặc định.
   - Lịch chạy (schedule) của các CronJob (`"0 2 * * *"` cho Checker, `"0 3 * * *"` cho Renewal).
   - Cấu hình số ngày sắp hết hạn chứng chỉ (`checkDays: 40`).
   - Cấu hình về hình ảnh Docker (`image.repository`, `image.tag`), tài nguyên (CPU/Memory) cho các container.
   - Tùy chọn bật/tắt (`enabled: true/false`) cho từng thành phần.
   - Cấu hình Telegram Bot Token để gửi cảnh báo (`telegramBotToken`, `telegramChatId`).
3. **Thư mục `templates/`**: Chứa các khuôn mẫu sẽ biến thành resource trên Kubernetes.
   - **`_helpers.tpl`**: Đây là file đặc biệt không tạo ra resource K8s cụ thể nào cả. Nó chứa các đoạn mã tái sử dụng (có thể coi như các hàm/macro), ví dụ như cách tạo ra tên chung (`fullname`), tạo các nhãn (labels) tiêu chuẩn. Việc này giúp các file YAML khác không bị lặp lại code (DRY - Don't Repeat Yourself).
   - **`cert-checker-cronjob.yaml`**: Template định nghĩa một `CronJob` trên K8s. Nhiệm vụ của nó là định kỳ quét các chứng chỉ ETCD xem có sắp hết hạn không (dựa trên số `checkDays`), và kiểm tra cấu hình health probe. Kết quả sẽ được ghi vào một K8s ConfigMap có tên `etcd-renewal`.
   - **`cert-renewal-cronjob.yaml`**: Template cho `CronJob` thứ hai. Nó sẽ đọc ConfigMap `etcd-renewal` do quá trình checker tạo ra. Nếu thấy có ETCD nào cần gia hạn, nó sẽ tiến hành thay mới chứng chỉ, cập nhật health check, khởi động lại các pod và gửi thông báo qua Telegram.
   - **Các file `*-role.yaml`, `*-rolebinding.yaml`, `*-serviceaccount.yaml`**: Đây là các file cấu hình phân quyền RBAC (Role-Based Access Control). Chúng cấp các đặc quyền truy cập K8s API cần thiết cho các CronJob. Ví dụ: CronJob cần quyền đọc Secret để lấy chứng chỉ, quyền sửa StatefulSet để khởi động lại Pod.

---

## 3. Cách các template cấu hình và tương tác với nhau

### Cách Helm Template hoạt động
Trong các file thuộc thư mục `templates/`, bạn sẽ thấy các cú pháp đặc biệt nằm trong cặp dấu ngoặc nhọn kép `{{ }}`. Đây là cú pháp của ngôn ngữ Go Template mà Helm sử dụng.

Ví dụ trong file `cert-checker-cronjob.yaml`:
```yaml
schedule: {{ .Values.certChecker.schedule | quote }}
```
**Quá trình xử lý của Helm (Rendering):**
1. Helm đọc file template này.
2. Nó tìm đến file `values.yaml`, tra cứu lấy giá trị của biến `certChecker.schedule` (mặc định là `"0 2 * * *"`).
3. Hàm `| quote` được sử dụng để đảm bảo giá trị sinh ra luôn được bao bọc bởi dấu ngoặc kép, giúp định dạng YAML hợp lệ và an toàn.
4. Nó thay thế toàn bộ khối `{{ ... }}` bằng giá trị chuỗi đã được xử lý và sinh ra manifest K8s cuối cùng.

Một ví dụ khác về việc sử dụng helper từ `_helpers.tpl` bên trong file `cert-checker-cronjob.yaml`:
```yaml
labels:
  {{- include "etcd-manager.certChecker.labels" . | nindent 4 }}
```
Lệnh `include` sẽ gọi đoạn code đã định nghĩa sẵn trong `_helpers.tpl` để sinh ra một bộ labels chuẩn. Lệnh `nindent 4` sẽ thêm dấu xuống dòng và thụt lề 4 khoảng trắng để đảm bảo YAML được căn lề chính xác.

### Kiến trúc tương tác của ứng dụng trong `etcd-manager`

Bản thân ứng dụng quản lý ETCD này được thiết kế theo dạng luồng hoạt động (workflow) thông qua sự kết hợp của các K8s resources:

1. **Người dùng triển khai:** Bạn tùy chỉnh file `values.yaml` (hoặc truyền tham số khi chạy lệnh `helm install`) để khai báo Token Telegram và các cài đặt lịch chạy.
2. **Tạo tài nguyên:** Helm áp dụng các template để tạo ra 2 CronJob, các ServiceAccount và thiết lập phân quyền (RBAC) trên cluster.
3. **Giai đoạn 1 - Checker (Kiểm tra):**
   - Chạy định kỳ theo lịch trình (ví dụ: 2h sáng).
   - Quét tất cả ETCD instances (dưới dạng các K8s StatefulSets) trong namespace được chỉ định.
   - Trích xuất K8s Secrets để kiểm tra xem chứng chỉ nào sắp hết thời hạn.
   - Lưu danh sách các instance có vấn đề (sắp hết hạn chứng chỉ hoặc thiếu health check) vào một **ConfigMap** tên là `etcd-renewal`.
4. **Giai đoạn 2 - Renewal (Gia hạn & Cập nhật):**
   - Chạy định kỳ sau Checker (ví dụ: 3h sáng).
   - Đọc danh sách từ **ConfigMap** `etcd-renewal` do Checker cung cấp.
   - Nếu có instance ETCD cần xử lý, nó tự động tương tác với K8s API để: thay thế/gia hạn chứng chỉ, cập nhật lại K8s Secrets tương ứng, định cấu hình lại các K8s Services/StatefulSets.
   - Khởi động lại các Pod để nhận chứng chỉ mới.
   - Dùng thông tin cấu hình Secret Telegram để gửi thông báo kết quả qua Telegram cho đội ngũ quản trị.

**Tổng kết:** 
Với biểu đồ Helm này, K8s ConfigMap đóng vai trò như một "cầu nối giao tiếp" giữa 2 CronJob độc lập. Helm đóng vai trò cung cấp giải pháp đóng gói toàn vẹn, cho phép bạn triển khai toàn bộ hệ thống Checker/Renewal cùng với các hệ thống phân quyền phức tạp của nó chỉ bằng một vài lệnh cấu hình đơn giản thông qua `values.yaml`.
