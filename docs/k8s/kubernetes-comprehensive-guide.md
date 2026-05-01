# Kubernetes: Từ Nền Tảng Đến Chuyên Sâu
### Góc nhìn của Senior Cloud/DevOps Engineer

---

## PHẦN 1: NHỮNG VIÊN GẠCH NỀN TẢNG — K8s Fundamentals & Architecture

---

### 1.1 Pod — Định nghĩa kỹ thuật

**Pod** là đơn vị scheduling và deployment nhỏ nhất trong Kubernetes. Về mặt kỹ thuật, một Pod là một **nhóm từ một hoặc nhiều container** chia sẻ cùng một tập hợp Linux namespaces và được đảm bảo chạy trên cùng một Node.

#### Các namespace được chia sẻ giữa các container trong cùng Pod

| Linux Namespace | Ý nghĩa kỹ thuật |
|---|---|
| **Network namespace** | Tất cả container trong Pod dùng chung 1 network stack, 1 địa chỉ IP, 1 bảng routing. Container A có thể gọi container B qua `localhost:PORT`. |
| **IPC namespace** | Cho phép giao tiếp qua POSIX message queues và System V IPC giữa các container. |
| **UTS namespace** | Dùng chung hostname. |
| **PID namespace** | Tuỳ cấu hình, có thể chia sẻ để một container nhìn thấy process của container khác. |

Ngược lại, mỗi container vẫn có **filesystem namespace riêng** (trừ khi được cấu hình Volume Mount chung).

#### Pause Container (Infrastructure Container)

Đây là chi tiết kỹ thuật quan trọng bậc nhất, thường bị bỏ qua.

Khi một Pod được tạo, `kubelet` khởi động **một container đặc biệt gọi là `pause` container** (image: `registry.k8s.io/pause`) trước tất cả các container khác. `pause` container:

1. **Là chủ sở hữu (owner) của network namespace**. Nó tạo ra và giữ network namespace sống.
2. **Là chủ sở hữu của IPC namespace**.
3. **Chạy một process duy nhất**: `pause`, vốn là một binary cực nhỏ chỉ gọi `pause()` syscall để sleep vĩnh viễn.
4. Các container ứng dụng (`nginx`, `app`, `sidecar`) sau đó được khởi động và join vào namespace của `pause` container thông qua flag `--network=container:<pause_id>` và `--ipc=container:<pause_id>`.

**Tại sao thiết kế này?** Nếu một container ứng dụng crash và được restart, network namespace không bị mất vì nó thuộc sở hữu của `pause` container — vốn không bao giờ restart (trừ khi toàn bộ Pod bị xóa). Điều này đảm bảo địa chỉ IP của Pod không thay đổi trong suốt vòng đời của Pod.

#### Volume dùng chung giữa các container trong Pod

```yaml
apiVersion: v1
kind: Pod
spec:
  volumes:
    - name: shared-data
      emptyDir: {}          # Volume tồn tại trong vòng đời Pod, lưu trên Node
  containers:
    - name: app
      image: nginx
      volumeMounts:
        - name: shared-data
          mountPath: /usr/share/nginx/html
    - name: content-generator
      image: busybox
      volumeMounts:
        - name: shared-data
          mountPath: /output
      command: ["sh", "-c", "echo 'hello' > /output/index.html && sleep 3600"]
```

Hai container này mount cùng một `emptyDir` volume — đây là cơ chế nền tảng của **Sidecar pattern**.

---

### 1.2 Kiến trúc Cluster: Control Plane và Worker Node

```
┌─────────────────────────────────────────────────────────────────┐
│                        CONTROL PLANE                            │
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐   │
│  │  API Server  │  │     etcd     │  │ Controller Manager │   │
│  │  (kube-      │  │  (key-value  │  │ (ReplicaSet ctrl,  │   │
│  │  apiserver)  │  │   store)     │  │  Node ctrl, etc.)  │   │
│  └──────┬───────┘  └──────────────┘  └────────────────────┘   │
│         │                                                       │
│  ┌──────┴───────┐                                              │
│  │  Scheduler   │                                              │
│  │(kube-        │                                              │
│  │ scheduler)   │                                              │
│  └──────────────┘                                              │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTPS/gRPC
              ┌──────────────┼──────────────┐
              │              │              │
   ┌──────────┴──┐  ┌────────┴────┐  ┌─────┴───────┐
   │  Worker 1   │  │  Worker 2   │  │  Worker 3   │
   │             │  │             │  │             │
   │ ┌─────────┐ │  │ ┌─────────┐ │  │ ┌─────────┐ │
   │ │ kubelet │ │  │ │ kubelet │ │  │ │ kubelet │ │
   │ ├─────────┤ │  │ ├─────────┤ │  │ ├─────────┤ │
   │ │kube-    │ │  │ │kube-    │ │  │ │kube-    │ │
   │ │proxy    │ │  │ │proxy    │ │  │ │proxy    │ │
   │ ├─────────┤ │  │ ├─────────┤ │  │ ├─────────┤ │
   │ │Container│ │  │ │Container│ │  │ │Container│ │
   │ │Runtime  │ │  │ │Runtime  │ │  │ │Runtime  │ │
   │ │(containerd│ │ │(containerd│ │ │(containerd│ │
   │ └─────────┘ │  │ └─────────┘ │  │ └─────────┘ │
   │   [Pod][Pod]│  │  [Pod][Pod] │  │  [Pod][Pod] │
   └─────────────┘  └─────────────┘  └─────────────┘
```

#### Các thành phần Control Plane

**kube-apiserver**
- Gateway duy nhất cho toàn bộ cluster. Mọi thao tác đọc/ghi đều phải qua đây.
- Thực hiện **Authentication** (ai gọi?), **Authorization** (có quyền không? — RBAC), **Admission Control** (validate/mutate object trước khi lưu).
- Sau khi validate, persist object vào **etcd** và trả về response.
- Expose giao thức RESTful + watch mechanism (server-sent events) để các component khác subscribe thay đổi.

**etcd**
- Distributed key-value store dựa trên Raft consensus algorithm.
- Là **nguồn sự thật duy nhất (single source of truth)** của cluster — mọi object (Pod spec, Service, ConfigMap, Secret, RBAC rules...) đều được lưu tại đây dưới dạng serialized protobuf.
- Không component nào đọc/ghi etcd trực tiếp ngoài API server.

**kube-scheduler**
- Liên tục watch API server qua **list-watch mechanism** để tìm các Pod có `spec.nodeName` trống (Pod chưa được assign).
- Khi tìm thấy, chạy thuật toán scheduling qua 2 phase:
  - **Filtering**: Loại bỏ các Node không đủ điều kiện (không đủ RAM, không đúng taint/toleration, không đủ port, v.v.).
  - **Scoring**: Chấm điểm các Node còn lại theo nhiều tiêu chí (ít Pod nhất, tài nguyên dư nhiều nhất, affinity rules...).
- Ghi `spec.nodeName` vào Pod object trên etcd thông qua API server. Scheduler **không** tự chạy container.

**kube-controller-manager**
- Chạy hàng chục **control loop** (controller) trong một process, mỗi loop phụ trách một loại resource:
  - `ReplicaSet controller`: Đảm bảo số Pod thực tế = số replica mong muốn.
  - `Node controller`: Detect Node unreachable và evict Pod.
  - `Endpoint controller`: Cập nhật Endpoints object khi Pod thay đổi.
  - Và nhiều controller khác (Deployment, Job, ServiceAccount, v.v.).
- Tất cả đều hoạt động theo mô hình **reconciliation loop**: `observe current state → compare with desired state → act to reconcile`.

#### Các thành phần Worker Node

**kubelet**
- Process chạy trên mỗi Node, là **agent** kết nối Node với Control Plane.
- Watch API server để nhận Pod spec được assign cho Node mình.
- Gọi **Container Runtime Interface (CRI)** để pull image và start container.
- Liên tục báo cáo trạng thái Node và Pod lên API server (CPU, RAM usage, Pod status).
- Chạy Liveness/Readiness/Startup probes và restart container khi cần.

**Container Runtime**
- Phần mềm thực sự tạo và quản lý container theo chuẩn **OCI (Open Container Initiative)**.
- Kubernetes giao tiếp với runtime qua gRPC interface **CRI**.
- Phổ biến nhất: **containerd** (default trong hầu hết distribution hiện đại), CRI-O.
- `containerd` gọi tiếp xuống `runc` để thực sự tạo Linux namespace và cgroup cho container.

**kube-proxy**
- Chạy trên mỗi Node, phụ trách implement **Service networking**.
- Mặc định dùng **iptables mode**: Tạo iptables rules để forward traffic đến Service IP → Pod IP.
- Mode hiệu suất cao hơn: **IPVS mode** dùng Linux kernel IPVS (IP Virtual Server) cho load balancing layer 4.
- kube-proxy **không** handle traffic trực tiếp — nó chỉ cấu hình kernel rules. Chính kernel forward packet.

---

### 1.3 Luồng thực thi: `kubectl run` đến khi Pod chạy

```
User
  │
  ▼  kubectl run nginx --image=nginx
[1] kubectl → REST API call → kube-apiserver (HTTPS :6443)
              (với Bearer token hoặc client cert từ ~/.kube/config)

[2] kube-apiserver:
    - Authenticate request
    - Authorize (RBAC: có quyền create Pod không?)
    - Admission Controllers (validate, inject defaults như resource limits từ LimitRange)
    - Serialize Pod object → persist vào etcd
    - Return 201 Created với Pod object

[3] kube-scheduler (đang watch API server):
    - Nhận event: Pod mới với spec.nodeName = ""
    - Chạy Filter phase: loại bỏ Node không đủ điều kiện
    - Chạy Score phase: chọn Node tốt nhất
    - PATCH Pod object: set spec.nodeName = "worker-node-2"
    - API server lưu update vào etcd

[4] kubelet trên worker-node-2 (đang watch API server):
    - Nhận event: Pod mới được assign cho mình
    - Gọi containerd qua CRI gRPC:
      a. Pull image nginx (nếu chưa có trên Node)
      b. Tạo pause container (lấy network namespace)
      c. CNI plugin cấp IP cho Pod
      d. Tạo app container nginx, join vào network namespace của pause
    - Update Pod status → RUNNING
    - kubelet PATCH Pod status lên API server

[5] kube-apiserver lưu trạng thái mới vào etcd
    kubectl watch nhận event, hiển thị: "pod/nginx Running"
```

**Điểm then chốt**: Không có component nào giao tiếp trực tiếp với nhau. Tất cả đi qua API server như một message bus. Đây là thiết kế **loosely coupled** cho phép scale và fault tolerance.

---

## PHẦN 2: QUẢN LÝ WORKLOAD & MÔ HÌNH TRIỂN KHAI — Controllers

---

### 2.1 Tại sao Pod thuần không đủ

Pod là đối tượng ephemeral. Nếu Node crash, Pod trên đó **không tự động được tạo lại**. Pod không có built-in restart policy khi bị terminate. Đây là lý do cần các **Controllers** — các control loop tự động reconcile trạng thái thực tế về trạng thái mong muốn.

### 2.2 ReplicaSet

**ReplicaSet** đảm bảo luôn có đúng `N` replicas của Pod đang chạy.

**Cơ chế hoạt động**: ReplicaSet controller watch 3 thứ: ReplicaSet object, Pod objects khớp với `selector`. Mỗi khi số Pod thực tế lệch với `spec.replicas`, controller tạo thêm hoặc xóa bớt Pod.

```yaml
apiVersion: apps/v1
kind: ReplicaSet
metadata:
  name: nginx-rs
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx       # ReplicaSet "sở hữu" Pod có label này
  template:            # Pod template — được copy để tạo Pod
    metadata:
      labels:
        app: nginx
    spec:
      containers:
        - name: nginx
          image: nginx:1.25
```

**Hạn chế của ReplicaSet**: Không quản lý được rolling update. Nếu đổi image từ `nginx:1.25` sang `1.26`, RS chỉ apply cho Pod mới tạo, không tự rotate các Pod đang chạy.

### 2.3 Deployment

**Deployment** là layer trên ReplicaSet, cung cấp declarative update với rollout strategy.

```
Deployment
    │
    ├── ReplicaSet (nginx:1.25, replicas=3)   ← old, scale về 0 sau khi update xong
    │
    └── ReplicaSet (nginx:1.26, replicas=3)   ← new, được tạo khi update
```

Khi update Deployment (đổi image), Deployment controller tạo **ReplicaSet mới** và thực hiện rolling update bằng cách scale up RS mới và scale down RS cũ theo `RollingUpdateStrategy`.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
spec:
  replicas: 3
  selector:
    matchLabels:
      app: nginx
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1    # Tối đa 1 Pod unavailable trong lúc update
      maxSurge: 1          # Tối đa 1 Pod tạm thời vượt quá replicas
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
        - name: nginx
          image: nginx:1.26
```

**Rollback**: `kubectl rollout undo deployment/nginx-deployment` — Deployment controller scale lại RS cũ lên, RS mới về 0.

### 2.4 Deployment vs StatefulSet — Sự khác biệt cốt lõi

| Thuộc tính | Deployment (Stateless) | StatefulSet (Stateful) |
|---|---|---|
| **Pod identity** | Ngẫu nhiên: `nginx-7d5f9-xkp2z` | Cố định, có thứ tự: `postgres-0`, `postgres-1` |
| **Start/Stop order** | Song song, không thứ tự | Tuần tự: `0 → 1 → 2` khi start, `2 → 1 → 0` khi stop |
| **Storage** | Volume dùng chung hoặc không cần persistent | Mỗi Pod có **PersistentVolumeClaim riêng** (volumeClaimTemplates) |
| **DNS** | Service: `postgres.default.svc.cluster.local` | Pod có DNS riêng: `postgres-0.postgres.default.svc.cluster.local` |
| **Update** | Rolling update đồng thời | Rolling update tuần tự, Pod `N` xong mới update Pod `N-1` |

**Tại sao Database bắt buộc dùng StatefulSet?**

PostgreSQL trong chế độ replication (primary + replica) có các yêu cầu không thể đáp ứng bởi Deployment:

1. **Primary phải khởi động trước replica**: Yêu cầu ordered pod startup — StatefulSet đảm bảo `postgres-0` (primary) online trước khi `postgres-1` (replica) start.
2. **Replica kết nối với primary qua hostname cố định**: Replica config `primary_conninfo = host=postgres-0.postgres` — hostname `postgres-0` là stable và predictable. Deployment không có stable hostname.
3. **Mỗi instance cần storage riêng biệt**: Primary không thể share WAL (Write-Ahead Log) directory với replica. `volumeClaimTemplates` đảm bảo `postgres-0` có PVC riêng, `postgres-1` có PVC riêng.
4. **Khi Pod restart, phải bind lại đúng volume cũ**: `postgres-0` sau khi restart phải mount lại đúng PVC của nó (không bị mount nhầm data của replica).

### 2.5 DaemonSet

DaemonSet đảm bảo **mỗi Node** (hoặc Node được chọn bởi selector) chạy **đúng một** Pod instance.

**Cơ chế**: DaemonSet controller watch Node events. Khi Node mới join cluster, controller tự động tạo Pod trên đó. Khi Node bị xóa, Pod bị garbage collect.

**Use cases thực tế**:
- Log collector (Fluentd, Fluent Bit): Cần đọc log trên mọi Node
- Monitoring agent (Prometheus Node Exporter): Thu thập metrics từng Node
- Network plugin (Calico, Cilium): Phải chạy trên mọi Node để cấu hình network
- Storage driver (Ceph, GlusterFS client)

```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: fluentd
spec:
  selector:
    matchLabels:
      name: fluentd
  template:
    spec:
      tolerations:          # Để có thể chạy trên Control Plane node nếu cần
        - key: node-role.kubernetes.io/control-plane
          effect: NoSchedule
      containers:
        - name: fluentd
          image: fluent/fluentd:v1.16
          volumeMounts:
            - name: varlog
              mountPath: /var/log
      volumes:
        - name: varlog
          hostPath:
            path: /var/log  # Mount thư mục log của Node host
```

### 2.6 Job và CronJob

**Job** chạy Pod đến khi hoàn thành (`Completed`), không phải chạy liên tục như Deployment.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: db-migration
spec:
  completions: 1         # Cần 1 Pod complete thành công
  parallelism: 1         # Chạy tối đa 1 Pod song song
  backoffLimit: 4        # Retry tối đa 4 lần nếu fail
  template:
    spec:
      restartPolicy: OnFailure   # Pod: Never hoặc OnFailure (không dùng Always)
      containers:
        - name: migration
          image: myapp:latest
          command: ["python", "manage.py", "migrate"]
```

**CronJob** tạo Job theo lịch cron:

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: backup
spec:
  schedule: "0 2 * * *"          # Chạy lúc 2:00 AM mỗi ngày
  successfulJobsHistoryLimit: 3   # Giữ lại 3 Job completed gần nhất
  failedJobsHistoryLimit: 1
  jobTemplate:
    spec:
      template:
        spec:
          restartPolicy: OnFailure
          containers:
            - name: backup
              image: postgres:15
              command: ["pg_dump", "-h", "postgres", "mydb"]
```

### 2.7 Sidecar Pattern

**Sidecar pattern** là mô hình thiết kế trong đó một container phụ (sidecar) chạy cùng container chính trong một Pod để bổ sung chức năng mà không thay đổi container chính.

**Bài toán thực tế**: Log aggregation. Container ứng dụng ghi log ra file. Sidecar container đọc file đó và forward đến Elasticsearch.

```yaml
apiVersion: v1
kind: Pod
spec:
  volumes:
    - name: log-volume
      emptyDir: {}
  containers:
    # Container chính: chỉ quan tâm business logic
    - name: app
      image: nestjs-app:latest
      volumeMounts:
        - name: log-volume
          mountPath: /app/logs

    # Sidecar: chỉ quan tâm log shipping
    - name: log-shipper
      image: fluent/fluent-bit:latest
      volumeMounts:
        - name: log-volume
          mountPath: /logs
          readOnly: true
      env:
        - name: ELASTICSEARCH_HOST
          value: "elasticsearch.logging.svc.cluster.local"
```

**Use cases khác của Sidecar**:
- **Service Mesh proxy** (Envoy/Istio): Intercept toàn bộ network traffic của container chính để implement mTLS, circuit breaking, tracing.
- **Config reloader**: Watch ConfigMap changes và reload config của app chính.
- **Secret rotation**: Fetch secret từ Vault và write vào shared volume.

---

## PHẦN 3: MẠNG LƯỚI — Networking & Định tuyến

---

### 3.1 Service: ClusterIP, NodePort, LoadBalancer

**Service** là abstraction layer cung cấp stable IP và DNS name cho một nhóm Pod (được chọn bởi label selector), cùng với built-in load balancing.

#### ClusterIP (default)

Cấp một **Virtual IP (VIP)** chỉ accessible trong nội bộ cluster. kube-proxy cấu hình iptables/IPVS để forward traffic từ ClusterIP đến Pod IPs.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: backend-api
spec:
  type: ClusterIP          # Default type
  selector:
    app: backend           # Forward đến Pod có label này
  ports:
    - protocol: TCP
      port: 80             # Service port (VIP:80)
      targetPort: 3000     # Container port
# => ClusterIP: 10.96.45.123 (assigned tự động)
# => DNS: backend-api.default.svc.cluster.local
```

#### NodePort

Expose Service trên một port của **tất cả các Node** trong cluster (port range: 30000–32767). Traffic đến `NodeIP:NodePort` được forward vào Service, rồi đến Pod.

```
Internet → NodeIP:30080 → iptables rule → ClusterIP:80 → Pod:3000
```

```yaml
spec:
  type: NodePort
  ports:
    - port: 80
      targetPort: 3000
      nodePort: 30080      # Nếu không set, K8s tự assign trong range 30000-32767
```

**Hạn chế**: Lộ diện port trực tiếp trên Node. Thường không dùng cho production ngoài mục đích testing nhanh.

#### LoadBalancer

Yêu cầu cluster integration với **cloud provider** (AWS, GCP, Azure) hoặc bare-metal load balancer (MetalLB). Cloud provider provision một External Load Balancer (AWS ELB, GCP Network LB) và forward traffic vào NodePort của cluster.

```
Internet → External LB (IP: 52.x.x.x) → NodePort → ClusterIP → Pod
```

```yaml
spec:
  type: LoadBalancer
  ports:
    - port: 443
      targetPort: 3000
# Cloud provider assign: EXTERNAL-IP: 52.34.123.45
```

**So sánh tổng hợp**:

| Type | Accessible từ | Use case |
|---|---|---|
| ClusterIP | Trong cluster | Internal service-to-service communication |
| NodePort | LAN/Internet (qua Node IP) | Dev/test, bare-metal không có cloud LB |
| LoadBalancer | Internet (qua External LB) | Production external traffic |

### 3.2 CoreDNS — Phân giải tên miền nội bộ

**CoreDNS** là DNS server chạy trong cluster (thường dưới dạng Deployment trong namespace `kube-system`). Mọi Pod mặc định có `/etc/resolv.conf` trỏ đến ClusterIP của CoreDNS service.

**Cấu trúc DNS name đầy đủ của Service**:

```
<service-name>.<namespace>.svc.<cluster-domain>
# Ví dụ:
postgres.database.svc.cluster.local
```

**Search domain mechanism**: `/etc/resolv.conf` trong Pod có:
```
search default.svc.cluster.local svc.cluster.local cluster.local
nameserver 10.96.0.10
```

Khi `backend` Pod gọi `postgres`:
1. DNS query: `postgres` → expand thành `postgres.default.svc.cluster.local`
2. CoreDNS nhận query, lookup trong cache hoặc etcd
3. Trả về ClusterIP của Service `postgres`
4. Pod kết nối đến ClusterIP, iptables/IPVS forward đến Pod IP

**Cross-namespace**: Pod trong namespace `app` gọi Service trong namespace `database`:
- Phải dùng FQDN: `postgres.database.svc.cluster.local` hoặc short form `postgres.database`

**Headless Service** (dùng với StatefulSet):

```yaml
spec:
  clusterIP: None    # Headless — không có VIP
```

CoreDNS trả về **A records trỏ trực tiếp đến Pod IPs**, và tạo DNS records cho từng Pod: `postgres-0.postgres.database.svc.cluster.local`.

### 3.3 Ingress

**Ingress** là K8s resource định nghĩa routing rules ở tầng HTTP/HTTPS (Layer 7). Bản thân Ingress object chỉ là config — cần **Ingress Controller** (một Pod thực sự chạy reverse proxy) để implement các rules đó.

**Ingress Controllers phổ biến**: NGINX Ingress Controller, Traefik, Kong, AWS ALB Ingress Controller.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: main-ingress
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /
    cert-manager.io/cluster-issuer: letsencrypt-prod
spec:
  ingressClassName: nginx
  tls:
    - hosts:
        - api.example.com
      secretName: api-tls-cert
  rules:
    - host: api.example.com
      http:
        paths:
          - path: /api
            pathType: Prefix
            backend:
              service:
                name: backend-api
                port:
                  number: 80
          - path: /
            pathType: Prefix
            backend:
              service:
                name: frontend
                port:
                  number: 80
    - host: admin.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: admin-panel
                port:
                  number: 80
```

**Luồng traffic**:
```
Client → DNS → External LB IP → Ingress Controller Pod
                                      │
                                      ├─ /api → backend-api Service → Pod
                                      └─ /    → frontend Service → Pod
```

Ingress Controller cũng handle **TLS termination** — decrypt HTTPS traffic và forward HTTP nội bộ đến Service.

---

## PHẦN 4: LƯU TRỮ & QUẢN LÝ CẤU HÌNH

---

### 4.1 PersistentVolume, PersistentVolumeClaim, StorageClass

**Vấn đề**: `emptyDir` volume mất khi Pod bị xóa. Database cần storage tồn tại độc lập với Pod lifecycle.

**PersistentVolume (PV)** là resource represent một piece of storage thực tế đã được provision (một disk trên cloud, NFS share, local disk). PV có lifecycle độc lập với Pod.

**PersistentVolumeClaim (PVC)** là yêu cầu storage từ phía user/workload. PVC chỉ quan tâm "tôi cần 10Gi storage với access mode ReadWriteOnce" mà không cần biết storage thực sự là gì.

**Binding**: K8s control plane tự động match PVC với PV phù hợp (dung lượng ≥ yêu cầu, access mode tương thích). Một PV chỉ có thể bind với một PVC tại một thời điểm (với mode RWO).

**StorageClass** cho phép **dynamic provisioning**: Thay vì admin phải tạo PV trước, khi PVC được tạo, StorageClass controller tự động provision PV mới (ví dụ: tạo AWS EBS volume).

```yaml
# StorageClass — Admin định nghĩa
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: fast-ssd
provisioner: ebs.csi.aws.com      # CSI driver cho AWS EBS
parameters:
  type: gp3
  iops: "3000"
  throughput: "125"
reclaimPolicy: Retain              # PV không bị xóa khi PVC bị xóa
volumeBindingMode: WaitForFirstConsumer  # Delay provision đến khi Pod được schedule

---
# PVC — Developer/Workload yêu cầu
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-data
spec:
  accessModes:
    - ReadWriteOnce          # Chỉ mount vào 1 Node tại một thời điểm
  storageClassName: fast-ssd
  resources:
    requests:
      storage: 50Gi

---
# Pod sử dụng PVC
spec:
  volumes:
    - name: postgres-storage
      persistentVolumeClaim:
        claimName: postgres-data
  containers:
    - name: postgres
      volumeMounts:
        - name: postgres-storage
          mountPath: /var/lib/postgresql/data
```

**Access Modes**:
- `ReadWriteOnce (RWO)`: Mount read-write bởi 1 Node. Dùng cho database.
- `ReadOnlyMany (ROX)`: Mount read-only bởi nhiều Node.
- `ReadWriteMany (RWX)`: Mount read-write bởi nhiều Node. Cần NFS hoặc distributed filesystem.

### 4.2 ConfigMap và Secret

**ConfigMap** lưu trữ non-sensitive configuration data dưới dạng key-value.

**Secret** lưu trữ sensitive data (base64-encoded, có thể encrypt at rest với etcd encryption). Thực tế nên kết hợp với external secret managers (Vault, AWS Secrets Manager + External Secrets Operator).

#### Inject vào Pod: 2 phương thức

**Phương thức 1: Environment Variables**

```yaml
# ConfigMap
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  DATABASE_HOST: "postgres.database.svc.cluster.local"
  LOG_LEVEL: "info"
  MAX_CONNECTIONS: "100"

---
# Secret
apiVersion: v1
kind: Secret
metadata:
  name: app-secrets
type: Opaque
data:
  DATABASE_PASSWORD: cGFzc3dvcmQxMjM=   # base64("password123")
  JWT_SECRET: c2VjcmV0a2V5              # base64("secretkey")

---
# Inject vào Pod
spec:
  containers:
    - name: app
      envFrom:
        - configMapRef:
            name: app-config       # Inject tất cả keys từ ConfigMap
        - secretRef:
            name: app-secrets      # Inject tất cả keys từ Secret
      env:
        - name: POD_NAME           # Inject từng key cụ thể + Downward API
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
```

**Phương thức 2: Volume Mount**

```yaml
spec:
  volumes:
    - name: config-volume
      configMap:
        name: app-config
    - name: secret-volume
      secret:
        secretName: app-secrets
        defaultMode: 0400          # File permissions: owner read-only
  containers:
    - name: app
      volumeMounts:
        - name: config-volume
          mountPath: /etc/config   # Mỗi key = 1 file trong thư mục này
        - name: secret-volume
          mountPath: /etc/secrets
          readOnly: true
```

**So sánh 2 phương thức**:

| | Environment Variables | Volume Mount |
|---|---|---|
| **Update** | Pod phải restart để nhận giá trị mới | File tự động cập nhật khi ConfigMap/Secret thay đổi (sau ~1 phút) |
| **Dùng cho** | Config đơn giản, biến môi trường | TLS certificates, config files phức tạp, dynamic reload |
| **Secret security** | Secret value có thể lộ qua `ps aux`, `/proc/env` | Safer — file trong tmpfs, dễ kiểm soát permission |

---

## PHẦN 5: PHÂN BỔ NÂNG CAO & PHÂN QUYỀN

---

### 5.1 Resource Requests và Limits

```yaml
spec:
  containers:
    - name: app
      resources:
        requests:
          memory: "256Mi"    # Minimum đảm bảo cho Pod. Scheduler dùng để filter/score Node.
          cpu: "250m"        # 250 millicores = 0.25 CPU core
        limits:
          memory: "512Mi"    # Hard limit. Vượt quá → OOMKilled
          cpu: "500m"        # Soft limit. CPU throttled nếu vượt, KHÔNG bị kill
```

**Cơ chế chi tiết**:
- `requests` → Kubernetes scheduler dùng để quyết định Node nào có đủ chỗ.
- `limits.memory` → Kernel cgroup memory limit. Vượt quá → `OOMKilled` (process bị kill, container restart).
- `limits.cpu` → Kernel CFS quota. Vượt quá → CPU bị throttle (chạy chậm), **không bị kill**.

**Quality of Service (QoS) Classes**:
- `Guaranteed`: `requests == limits` cho tất cả resources → Được evict cuối cùng khi Node thiếu tài nguyên.
- `Burstable`: `requests < limits` → Trung bình.
- `BestEffort`: Không set requests/limits → Bị evict đầu tiên.

### 5.2 Namespace và ResourceQuota

**Namespace** là logical partition trong cluster, cung cấp scope cho resource names và access control.

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: production-quota
  namespace: production
spec:
  hard:
    requests.cpu: "10"
    requests.memory: 20Gi
    limits.cpu: "20"
    limits.memory: 40Gi
    count/pods: "50"
    count/services: "20"
    persistentvolumeclaims: "10"
```

### 5.3 Advanced Scheduling

#### nodeSelector

Cơ chế đơn giản nhất: Pod chỉ được schedule lên Node có label matching.

```yaml
spec:
  nodeSelector:
    kubernetes.io/arch: amd64
    node-type: high-memory
```

#### Node Affinity

Phiên bản nâng cao của nodeSelector với operators phong phú hơn và soft/hard rules.

```yaml
spec:
  affinity:
    nodeAffinity:
      requiredDuringSchedulingIgnoredDuringExecution:   # Hard rule
        nodeSelectorTerms:
          - matchExpressions:
              - key: topology.kubernetes.io/zone
                operator: In
                values: ["us-east-1a", "us-east-1b"]
      preferredDuringSchedulingIgnoredDuringExecution:  # Soft rule (best effort)
        - weight: 80
          preference:
            matchExpressions:
              - key: node-type
                operator: In
                values: ["spot"]
```

#### Taints và Tolerations

**Taint** được gắn vào Node, **ngăn** Pod bình thường được schedule lên đó. **Toleration** được khai báo trong Pod spec, cho phép Pod **chịu đựng** taint của Node.

**Use case: Node GPU chỉ dành cho AI workloads**

```bash
# Admin taint Node GPU
kubectl taint nodes gpu-node-1 hardware=gpu:NoSchedule
```

```yaml
# Pod AI workload có toleration phù hợp
spec:
  tolerations:
    - key: "hardware"
      operator: "Equal"
      value: "gpu"
      effect: "NoSchedule"
  nodeSelector:
    hardware: gpu            # Chỉ schedule lên Node có GPU
  containers:
    - name: ai-training
      resources:
        limits:
          nvidia.com/gpu: 1  # Request 1 GPU
```

**Effect types**:
- `NoSchedule`: Pod không được schedule lên Node (nếu không có toleration).
- `PreferNoSchedule`: Soft version — tránh schedule lên Node này nếu có thể.
- `NoExecute`: Evict Pod đang chạy không có toleration + không schedule mới.

### 5.4 RBAC (Role-Based Access Control)

**Các đối tượng RBAC**:
- `Role` / `ClusterRole`: Tập hợp permissions (verbs trên resources).
- `RoleBinding` / `ClusterRoleBinding`: Gán Role cho Subject (User, Group, ServiceAccount).
- `ServiceAccount`: Identity cho Pod (không phải con người).

```yaml
# Role — chỉ áp dụng trong namespace "production"
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: pod-reader
  namespace: production
rules:
  - apiGroups: [""]           # "" = core API group
    resources: ["pods", "pods/log"]
    verbs: ["get", "list", "watch"]

---
# ClusterRole — áp dụng toàn cluster
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: secret-manager
rules:
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get", "create", "update", "delete"]

---
# ServiceAccount cho Pod
apiVersion: v1
kind: ServiceAccount
metadata:
  name: backend-sa
  namespace: production

---
# RoleBinding — gán Role cho ServiceAccount
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: backend-pod-reader
  namespace: production
subjects:
  - kind: ServiceAccount
    name: backend-sa
    namespace: production
roleRef:
  kind: Role
  name: pod-reader
  apiGroup: rbac.authorization.k8s.io

---
# Pod sử dụng ServiceAccount
spec:
  serviceAccountName: backend-sa
  containers:
    - name: app
      # K8s tự mount token vào /var/run/secrets/kubernetes.io/serviceaccount/
```

### 5.5 Network Policy

**Network Policy** là firewall rules ở tầng Pod, implement bởi CNI plugin (Calico, Cilium, Weave). Mặc định K8s **không có** Network Policy — tất cả Pod có thể giao tiếp với nhau tự do.

```yaml
# Chính sách: Backend chỉ nhận traffic từ Frontend, không từ nguồn khác
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: backend-ingress-policy
  namespace: production
spec:
  podSelector:
    matchLabels:
      app: backend        # Áp dụng cho Pod backend
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: frontend   # Chỉ cho phép từ frontend Pod
        - namespaceSelector:
            matchLabels:
              name: monitoring  # Cho phép từ namespace monitoring (Prometheus)
      ports:
        - protocol: TCP
          port: 3000
  egress:
    - to:
        - podSelector:
            matchLabels:
              app: postgres
      ports:
        - protocol: TCP
          port: 5432
    - to:              # Cho phép DNS lookup
        - namespaceSelector: {}
      ports:
        - protocol: UDP
          port: 53
```

---

## PHẦN 6: VẬN HÀNH THỰC CHIẾN — Production-Grade Operations

---

### 6.1 Liveness, Readiness và Startup Probe

Ba loại probe cho kubelet biết trạng thái thực sự của container.

| Probe | Khi fail → | Mục đích |
|---|---|---|
| **Liveness** | Container bị **restart** | Phát hiện deadlock, memory leak, stuck state |
| **Readiness** | Pod bị **remove khỏi Service endpoints** (không nhận traffic mới) | Biết khi nào Pod sẵn sàng phục vụ request |
| **Startup** | Container bị **restart** | Cho app slow-start thời gian khởi động trước khi Liveness bắt đầu check |

```yaml
spec:
  containers:
    - name: api
      image: nestjs-api:latest
      startupProbe:            # Chạy TRƯỚC, disable Liveness/Readiness trong lúc này
        httpGet:
          path: /health
          port: 3000
        failureThreshold: 30   # Cho phép 30 lần fail
        periodSeconds: 10      # Total: 30 * 10 = 300s cho app khởi động

      livenessProbe:           # Chạy sau khi startupProbe success
        httpGet:
          path: /health/live   # Endpoint trả về 200 nếu app không bị stuck
          port: 3000
        initialDelaySeconds: 0 # Với startupProbe, không cần delay
        periodSeconds: 15
        failureThreshold: 3    # 3 lần fail liên tiếp → restart container

      readinessProbe:
        httpGet:
          path: /health/ready  # Endpoint check DB connection, queue, dependencies
          port: 3000
        periodSeconds: 5
        failureThreshold: 3    # 3 lần fail → remove khỏi Service endpoints
        successThreshold: 2    # Cần 2 lần success liên tiếp để được add lại
```

**Thiết kế endpoint `/health/ready`**:
```javascript
// NestJS example
@Get('/health/ready')
async readiness() {
  // Check tất cả dependencies
  await this.dataSource.query('SELECT 1');    // DB connection
  await this.redis.ping();                    // Cache connection
  return { status: 'ready' };                // 200 OK
  // Nếu throw exception → HTTP 500 → readiness fail → tắt traffic
}
```

### 6.2 Rolling Update: Cơ chế từng bước

Khi `kubectl set image deployment/api api=nestjs-api:v2`:

```
Initial state: [v1][v1][v1] replicas=3, maxUnavailable=1, maxSurge=1

Step 1: Tạo Pod v2 mới (surge: 4 Pod đang chạy)
        [v1][v1][v1][v2-starting]

Step 2: v2 Pod pass readiness probe → add vào Service endpoints
        [v1][v1][v1][v2-ready]

Step 3: Terminate 1 Pod v1 (unavailable: vẫn còn 3 ready)
        [v1][v1][v2-ready]

Step 4: Tạo Pod v2 mới thứ 2
        [v1][v1][v2-ready][v2-starting]

Step 5: v2-starting pass readiness → terminate v1
        [v1][v2-ready][v2-ready]

... lặp lại cho đến khi hoàn thành ...

Final:  [v2][v2][v2]
```

**Điểm quan trọng**: Readiness probe là yếu tố quyết định. Nếu v2 Pod không pass readiness, rolling update **sẽ dừng lại** (stall) thay vì tiến tiếp terminate v1. Đây là cơ chế tự bảo vệ — không bao giờ tất cả Pod cùng fail.

```yaml
spec:
  progressDeadlineSeconds: 600  # Sau 10 phút nếu chưa xong → mark Failed
  minReadySeconds: 30            # Pod phải ready ít nhất 30s trước khi tính là "available"
```

### 6.3 Horizontal Pod Autoscaler (HPA)

HPA tự động scale `replicas` của Deployment/StatefulSet dựa trên metrics.

**Kiến trúc**:
```
[App Pods] → [metrics-server / Prometheus Adapter] → [HPA Controller]
                (thu thập CPU/RAM/custom metrics)      (reconcile replicas)
```

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: backend-api
  minReplicas: 2
  maxReplicas: 20
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70    # Scale up khi CPU trung bình > 70% của requests
    - type: Resource
      resource:
        name: memory
        target:
          type: AverageValue
          averageValue: 400Mi
    - type: Pods                    # Custom metric từ Prometheus
      pods:
        metric:
          name: http_requests_per_second
        target:
          type: AverageValue
          averageValue: "1000"
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60    # Chờ 60s trước khi scale up lần tiếp
      policies:
        - type: Pods
          value: 4                      # Tối đa scale up 4 Pod mỗi lần
          periodSeconds: 60
    scaleDown:
      stabilizationWindowSeconds: 300   # Chờ 5 phút trước khi scale down (tránh flapping)
      policies:
        - type: Percent
          value: 10                     # Scale down tối đa 10% mỗi lần
          periodSeconds: 60
```

**Công thức tính replicas**:
```
desiredReplicas = ceil(currentReplicas * (currentMetricValue / desiredMetricValue))
# Ví dụ: 3 pods, CPU 90%, target 70%
# = ceil(3 * 90/70) = ceil(3.857) = 4 pods
```

---

## PHẦN 7: USE CASE THỰC TẾ — System Topology & Helm

---

### 7.1 Thiết kế K8s cho stack: Next.js + NestJS + PostgreSQL

```
┌─────────────────────────────────────────────────────────────────┐
│  Namespace: production                                          │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Ingress (NGINX)                                         │  │
│  │  api.example.com/api → backend-api-svc                   │  │
│  │  api.example.com/    → frontend-svc                      │  │
│  └──────────────────────────────────────────────────────────┘  │
│           │                          │                          │
│  ┌────────┴──────┐          ┌────────┴──────┐                  │
│  │   Deployment  │          │   Deployment  │                  │
│  │   (NestJS)    │          │   (Next.js)   │                  │
│  │  replicas: 3  │          │  replicas: 2  │                  │
│  │  HPA: 2-10    │          │  HPA: 2-6     │                  │
│  └───────┬───────┘          └───────────────┘                  │
│          │ Service: ClusterIP                                   │
│          │ backend-api:80 → pod:3000                           │
│          │                                                      │
│  ┌───────┴───────┐                                             │
│  │  StatefulSet  │                                             │
│  │  (PostgreSQL) │                                             │
│  │  replicas: 1* │                                             │
│  │  PVC: 50Gi    │                                             │
│  └───────────────┘                                             │
│  *Production: dùng managed DB (RDS) hoặc Postgres Operator     │
└─────────────────────────────────────────────────────────────────┘
```

**Quyết định thiết kế**:
- `Next.js` → `Deployment`: Stateless, mỗi request độc lập, có thể scale horizontally.
- `NestJS` → `Deployment`: API stateless (session lưu trong Redis hoặc JWT). Scale theo CPU/RPS.
- `PostgreSQL` → `StatefulSet`: Có persistent state, cần stable network identity, ordered operations.

**Service topology**:
- Frontend cần gọi Backend: `http://backend-api.production.svc.cluster.local`
- Backend cần gọi DB: `postgres-headless.production.svc.cluster.local` (headless service của StatefulSet)
- Frontend được expose ra ngoài qua Ingress.
- Backend và DB: ClusterIP only, không expose ra ngoài.

---

### 7.2 YAML Thực tế — NestJS Backend API

```yaml
# ============================================================
# backend-api/deployment.yaml
# ============================================================
apiVersion: apps/v1
kind: Deployment
metadata:
  name: backend-api
  namespace: production
  labels:
    app: backend-api
    version: "1.0.0"
spec:
  replicas: 3
  revisionHistoryLimit: 5          # Giữ 5 ReplicaSet cũ để rollback
  selector:
    matchLabels:
      app: backend-api
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 1
      maxSurge: 1
  template:
    metadata:
      labels:
        app: backend-api
        version: "1.0.0"
    spec:
      serviceAccountName: backend-sa
      # Tránh schedule nhiều Pod cùng 1 Node
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                labelSelector:
                  matchLabels:
                    app: backend-api
                topologyKey: kubernetes.io/hostname
      containers:
        - name: api
          image: registry.example.com/nestjs-api:1.0.0
          imagePullPolicy: IfNotPresent
          ports:
            - name: http
              containerPort: 3000
              protocol: TCP
          # ── Inject config ──
          envFrom:
            - configMapRef:
                name: backend-config
            - secretRef:
                name: backend-secrets
          env:
            - name: NODE_ENV
              value: "production"
            - name: POD_NAME
              valueFrom:
                fieldRef:
                  fieldPath: metadata.name
            - name: POD_NAMESPACE
              valueFrom:
                fieldRef:
                  fieldPath: metadata.namespace
          # ── Resource constraints ──
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          # ── Health probes ──
          startupProbe:
            httpGet:
              path: /health
              port: http
            failureThreshold: 30
            periodSeconds: 10        # Tổng 5 phút cho app startup
          livenessProbe:
            httpGet:
              path: /health/live
              port: http
            periodSeconds: 15
            failureThreshold: 3
            timeoutSeconds: 5
          readinessProbe:
            httpGet:
              path: /health/ready
              port: http
            initialDelaySeconds: 5
            periodSeconds: 5
            failureThreshold: 3
            successThreshold: 1
            timeoutSeconds: 3
          # ── Security ──
          securityContext:
            runAsNonRoot: true
            runAsUser: 1000
            readOnlyRootFilesystem: true
            allowPrivilegeEscalation: false
          volumeMounts:
            - name: tmp-dir
              mountPath: /tmp        # Cần vì readOnlyRootFilesystem=true
      volumes:
        - name: tmp-dir
          emptyDir: {}
      terminationGracePeriodSeconds: 30   # 30s để graceful shutdown
      imagePullSecrets:
        - name: registry-credentials

---
# ============================================================
# backend-api/service.yaml
# ============================================================
apiVersion: v1
kind: Service
metadata:
  name: backend-api
  namespace: production
  labels:
    app: backend-api
spec:
  type: ClusterIP
  selector:
    app: backend-api
  ports:
    - name: http
      protocol: TCP
      port: 80
      targetPort: http    # Tham chiếu tên port "http" trong container

---
# ============================================================
# backend-api/configmap.yaml
# ============================================================
apiVersion: v1
kind: ConfigMap
metadata:
  name: backend-config
  namespace: production
data:
  DATABASE_HOST: "postgres-0.postgres-headless.production.svc.cluster.local"
  DATABASE_PORT: "5432"
  DATABASE_NAME: "appdb"
  REDIS_HOST: "redis.production.svc.cluster.local"
  LOG_LEVEL: "info"
  PORT: "3000"

---
# ============================================================
# backend-api/hpa.yaml
# ============================================================
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: backend-api-hpa
  namespace: production
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: backend-api
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300

---
# ============================================================
# backend-api/poddisruptionbudget.yaml
# Đảm bảo ít nhất 2 Pod available khi maintenance/drain Node
# ============================================================
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: backend-api-pdb
  namespace: production
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: backend-api
```

---

### 7.3 Helm — Package Manager cho Kubernetes

#### Vấn đề với raw YAML

Khi quản lý nhiều environment (dev, staging, production), raw YAML có các vấn đề:

1. **Duplication**: 90% nội dung giống nhau, chỉ khác image tag, replicas, resource limits, domain.
2. **Versioning**: Không có cơ chế package/version cho toàn bộ application (gồm nhiều YAML files).
3. **Rollback**: `kubectl rollout undo` chỉ rollback Deployment, không rollback toàn bộ (ConfigMap, Secret, Service thay đổi).
4. **Dependency management**: Ứng dụng phụ thuộc vào PostgreSQL, Redis — cần install theo đúng thứ tự.

#### Helm là gì

**Helm** là package manager cho Kubernetes. Đơn vị package là **Chart** — một thư mục chứa:

```
backend-api/
├── Chart.yaml          # Metadata: name, version, description, dependencies
├── values.yaml         # Default values
├── values-prod.yaml    # Override cho production
├── templates/
│   ├── deployment.yaml
│   ├── service.yaml
│   ├── configmap.yaml
│   ├── hpa.yaml
│   ├── ingress.yaml
│   └── _helpers.tpl    # Template helpers (Go template functions)
└── charts/             # Dependency charts (sub-charts)
```

**Go template trong Helm**:

```yaml
# templates/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "backend-api.fullname" . }}
  namespace: {{ .Release.Namespace }}
  labels:
    {{- include "backend-api.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  template:
    spec:
      containers:
        - name: {{ .Chart.Name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          {{- if .Values.readinessProbe.enabled }}
          readinessProbe:
            httpGet:
              path: {{ .Values.readinessProbe.path }}
              port: http
          {{- end }}
```

**values.yaml** (default):
```yaml
replicaCount: 2
image:
  repository: registry.example.com/nestjs-api
  tag: ""            # Mặc định dùng Chart.AppVersion
resources:
  requests:
    memory: "256Mi"
    cpu: "250m"
  limits:
    memory: "512Mi"
    cpu: "500m"
readinessProbe:
  enabled: true
  path: /health/ready
```

**values-prod.yaml** (override cho production):
```yaml
replicaCount: 5
image:
  tag: "1.5.2"
resources:
  requests:
    memory: "512Mi"
    cpu: "500m"
  limits:
    memory: "1Gi"
    cpu: "1000m"
```

**Các lệnh Helm thiết yếu**:

```bash
# Install Chart
helm install backend-api ./backend-api \
  -f values-prod.yaml \
  --namespace production \
  --create-namespace \
  --set image.tag=1.5.2

# Upgrade (rolling update + config change)
helm upgrade backend-api ./backend-api \
  -f values-prod.yaml \
  --set image.tag=1.6.0

# Rollback về revision trước
helm rollback backend-api 3   # Rollback về revision 3

# Xem lịch sử
helm history backend-api

# Dùng Chart từ public repository (ví dụ: PostgreSQL)
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install postgres bitnami/postgresql \
  --set auth.postgresPassword=secret \
  --set primary.persistence.size=50Gi
```

#### Helm vs Raw YAML — Khi nào dùng gì

| Tiêu chí | Raw YAML | Helm |
|---|---|---|
| Độ phức tạp thấp, 1 env | ✅ Đủ dùng | Overkill |
| Multi-environment | ❌ Duplication | ✅ Values override |
| Team lớn, cần versioning | ❌ Khó manage | ✅ Chart version |
| Reuse community charts (PostgreSQL, Redis, Prometheus) | ❌ Phải tự viết | ✅ Helm Hub |
| Rollback toàn bộ release | ❌ Không hỗ trợ | ✅ `helm rollback` |
| CI/CD pipeline đơn giản | ✅ `kubectl apply` | Cần cài Helm client |

**Thực tế production**: Helm cho ứng dụng phức tạp. **Kustomize** là lựa chọn thay thế (built into `kubectl`, không cần template engine) cho các trường hợp trung bình.

---

## TỔNG KẾT — Mental Model

```
┌─────────────────────────────────────────────────────────────────┐
│  Desired State (bạn khai báo)  →  etcd (lưu trữ)               │
│                                         ↓                       │
│              Controller Manager (reconcile loop)                │
│                    ↓ tạo/xóa Pod                                │
│              Scheduler (assign Node)                            │
│                    ↓ nodeName                                   │
│              kubelet (chạy container qua containerd)            │
│                    ↓ báo cáo status                             │
│  Actual State (reality)       →  etcd (cập nhật)               │
└─────────────────────────────────────────────────────────────────┘
```

Toàn bộ Kubernetes là một **distributed reconciliation engine**: liên tục so sánh desired state với actual state và hành động để thu hẹp khoảng cách. Hiểu nguyên lý này là hiểu Kubernetes.
