# Multi-Zone Migration - Code Changes (Detailed)

## 1) Scope and design target

Tai lieu nay mo ta chi tiet thay doi code theo huong:

- Su dung schema duy nhat `zones[]` cho ca 1-zone va multi-zone.
- 1-zone = `zones[]` co 1 phan tu.
- multi-zone = `zones[]` co nhieu phan tu.
- Khong dua `zone -> subnet` vao `PostgresClusterConfigRefer`.
- Load Balancer giu o trang thai deferred.

---

## 2) Input / DTO

### Truoc thay doi

- Request chinh tap trung vao cac field phang (`zoneId`, `managementSubnetId`, ...).
- Cac workflow de bi rang buoc single-zone, kho mo rong fault domain.

### Sau thay doi

- Request dung `zones[]`:
  - Moi phan tu gom `zoneId`, `managementSubnetId`, `privateSubnetId` (co the optional theo giai doan).
- DTO tao cluster va scale-out dong bo theo `zones[]`.

Pseudo-DTO:

```java
@NotNull
@Size(min = 1)
private List<ZoneConfigDto> zones;

public class ZoneConfigDto {
    @NotBlank
    private String zoneId;
    @NotBlank
    private String managementSubnetId;
    private String privateSubnetId;
}
```

### Vi sao phai thay doi

- Tranh schema phang khong mo rong khi so zone tang.
- Request la source-of-truth de workflow replay/debug de hon.
- Khong can map subnet ben ngoai qua config refer.

---

## 3) Create workflow - placement map theo zone

**File chinh:** `CreatePostgresClusterBackendWorkflowImpl.java`

### Truoc thay doi

- Dung 1 bien `placementGroupId` cho toan bo nodegroup.
- Nodegroup tao o zone khac van co nguy co dung nham placementGroup cua zone dau.
- Retry phase kho dam bao tao lai dung placementGroup theo zone goc.

### Sau thay doi

- Dung `Map<String, String> zonePlacementGroupMap`.
- Round-robin zone theo `zones[]`.
- Moi zone se co placementGroup rieng:
  - Lan dau gap zone: tao nodegroup voi placementGroup NEW.
  - Sau khi tao xong: lay placementGroupId va luu vao map.
  - Cac nodegroup tiep theo cung zone: dung placementGroup EXISTING tu map.
- Retry dung lai `zoneId` va lookup `zonePlacementGroupMap` de dam bao tinh nhat quan.

Pseudo-code create phase:

```java
List<ZoneConfigDto> zones = request.getZones();
Map<String, String> zonePlacementGroupMap = new HashMap<>();
List<String> nodeGroupZones = new ArrayList<>();

for (int i = 0; i < nodeGroupNames.size(); i++) {
    ZoneConfigDto target = zones.get(i % zones.size());
    String zoneId = target.getZoneId();
    String subnetId = target.getManagementSubnetId();
    String pgId = zonePlacementGroupMap.get(zoneId);
    nodeGroupZones.add(zoneId);

    NodeGroupCreateRequestDto dto = nodeGroupActivities.buildCreateNodeGroupRequest(
        nodeGroupNames.get(i), pgId, namespace, request, zoneId, subnetId
    );
    NodeGroupCreateResponseDto res = nodeGroupActivities.createNodeGroup(dto);

    if (pgId == null) {
        String newPgId = nodeGroupWaitActivities.getNodeGroupPlacementGroupId(res.getId());
        zonePlacementGroupMap.put(zoneId, newPgId);
    }
}
```

Pseudo-code retry phase:

```java
String retryZone = nodeGroupZones.get(idx);
ZoneConfigDto target = zonesById.get(retryZone);
String retrySubnet = target.getManagementSubnetId();
String retryPgId = zonePlacementGroupMap.get(retryZone);
```

### Vi sao phai thay doi

- Placement group co rang buoc fault domain; khong the coi 1 PG la hop le cho moi zone.
- Map theo zone giup create/retry on dinh va dung thiet ke multi-zone.
- Tranh tinh trang skew placement khi round-robin.

---

## 4) Scale workflow - round-robin + placement map

**File chinh:** `ScalePostgresClusterBackendWorkflowImpl.java`

### Truoc thay doi

- Thuong lay placementGroupId tu node dau tien.
- Scale-out de bi bias ve zone da co node dau.

### Sau thay doi

- Build `zonePlacementGroupMap` tu nodegroup hien huu (zone label -> placementGroupId).
- Chon zone theo round-robin co tinh den `existingCount`.
- Lay subnet tu `zones[]`.
- Neu zone moi chua co PG: tao node dau trong zone do, sau do lay PG va put vao map.

Pseudo-code:

```java
Map<String, String> zonePlacementGroupMap = buildFromExistingNodeGroups(nodeGroupDetails);
List<ZoneConfigDto> zones = request.getZones();

for (int i = 0; i < nodeGroupNamesNew.size(); i++) {
    ZoneConfigDto target = zones.get((existingCount + i) % zones.size());
    String zoneId = target.getZoneId();
    String subnetId = target.getManagementSubnetId();
    String pgId = zonePlacementGroupMap.get(zoneId);

    NodeGroupCreateRequestDto dto = nodeGroupActivities.buildCreateNodeGroupRequest(
        nodeGroupNamesNew.get(i), pgId, namespace, createDto, zoneId, subnetId
    );
    NodeGroupCreateResponseDto res = nodeGroupActivities.createNodeGroup(dto);

    if (pgId == null) {
        zonePlacementGroupMap.put(zoneId, nodeGroupWaitActivities.getNodeGroupPlacementGroupId(res.getId()));
    }
}
```

### Vi sao phai thay doi

- Scale-out can giu can bang fault domain, khong duoc tiep tuc dồn mot zone.
- Placement map la dieu kien can de them node dung zone khi cluster mo rong.

---

## 5) NodeGroup activity signature

### Truoc thay doi

- `buildCreateNodeGroupRequest(...)` co xu huong phu thuoc subnet tu request-level.

### Sau thay doi

- Signature bat buoc nhan `zoneId`, `subnetId` tu workflow:

```java
NodeGroupCreateRequestDto buildCreateNodeGroupRequest(
    String nodeGroupName,
    String placementGroupId,
    String namespace,
    CreatePostgresClusterReqDto request,
    String zoneId,
    String subnetId
);
```

### Vi sao phai thay doi

- Dam bao nodegroup duoc tao bang du lieu da resolve theo zone.
- Giu separation ro rang giua orchestration (workflow) va request-builder (activity).

---

## 6) Scheduling / CRD (day du)

**Files:** `2_postgresql-deployment.yaml`, `Postgresql.java`, logic apply CRD trong activities

### 6.1 topologySpreadConstraints

#### Truoc thay doi

- Pod placement chu yeu dua vao nodeAffinity/tolerations.
- Scheduler khong bi bat buoc trai deu theo zone.

#### Sau thay doi

- Bo sung `topologySpreadConstraints` theo `topology.kubernetes.io/zone`.

YAML huong dan:

```yaml
additionalPodSpec:
  topologySpreadConstraints:
    - maxSkew: 1
      topologyKey: topology.kubernetes.io/zone
      whenUnsatisfiable: ScheduleAnyway
      labelSelector:
        matchLabels:
          application: spilo
          cluster-name: <namespace>-cluster
```

#### Vi sao phai thay doi

- Dat muc tieu HA AZ-level, tranh co-location replica cung zone.
- Ho tro multi-zone thuc chat thay vi chi multi-zone o tang nodegroup.

### 6.2 whenUnsatisfiable trade-off

#### Truoc thay doi

- Chua co quy tac ro rang cho truong hop cluster thieu tai nguyen theo zone.

#### Sau thay doi

- Dinh nghia ro:
  - `ScheduleAnyway`: uu tien availability, chap nhan lech tam thoi.
  - `DoNotSchedule`: uu tien strict spread, chap nhan pending neu khong du node.

#### Vi sao phai thay doi

- Tranh quyet dinh ngam trong qua trinh van hanh.
- Cho phep chon policy theo moi truong (dev/staging/prod).

### 6.3 podAntiAffinity

#### Truoc thay doi

- Chua co khuyen nghi ro rang giam co-location o cung topology domain.

#### Sau thay doi

- Khuyen nghi them `podAntiAffinity` theo nhan cluster, uu tien theo zone/hostname.

Pseudo-spec:

```yaml
additionalPodSpec:
  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
        - weight: 100
          podAffinityTerm:
            topologyKey: topology.kubernetes.io/zone
            labelSelector:
              matchLabels:
                application: spilo
                cluster-name: <namespace>-cluster
```

#### Vi sao phai thay doi

- Bo sung lop bao ve bo tri pod, giam rui ro dat replica gan nhau.

### 6.4 Postgresql.java / additionalPodSpec

#### Truoc thay doi

- Model co the chua mo rong de serialize additional pod scheduling fields.

#### Sau thay doi

- Mo rong model de ho tro `additionalPodSpec` khi patch/inject qua code.

#### Vi sao phai thay doi

- Tranh mat truong khi map object -> CRD.
- Dam bao cac scheduling settings di duoc tu code ra YAML/K8s object.

### 6.5 1-zone vs multi-zone ap dung scheduling

#### Truoc thay doi

- Chua neu ro hanh vi theo tung mode.

#### Sau thay doi

- 1-zone: scheduling config van hop le, khong pha workflow.
- multi-zone: spread/anti-affinity la bat buoc de dat muc tieu HA.

#### Vi sao phai thay doi

- Tranh hieu nham rang scheduling chi dung cho multi-zone moi.
- Giu 1 code path thong nhat, de van hanh va test de hon.

---

## 7) Checklist file can sua

1. `CreatePostgresClusterReqDto.java`
2. `ScalePostgresClusterReqDto.java`
3. `NodeGroupActivities.java`
4. `NodeGroupActivitiesImpl.java`
5. `CreatePostgresClusterBackendWorkflowImpl.java`
6. `ScalePostgresClusterBackendWorkflowImpl.java`
7. `2_postgresql-deployment.yaml`
8. `Postgresql.java`

---

## 8) Deferred - Load Balancer

## Phan LB khong doi trong scope hien tai. Chi danh dau de xu ly phase sau.

# Phan tich chi tiet: CreatePostgresClusterBackendWorkflowImpl.java

File: `src/main/java/com/engineering/temporal/workflow/impl/CreatePostgresClusterBackendWorkflowImpl.java`

Workflow nay co nhieu diem can sua cho multi-zone, khong chi phan create nodegroup. Duoi day liet ke **tat ca** cac diem can thay doi, kem code truoc/sau va ly do.

---

## Diem 1: Xoa default zoneId hardcode

### Truoc thay doi (line 72-74)

```java
if(request.getZoneId() == null){
    request.setZoneId("HCM03-1A");
}
```

### Sau thay doi

```java
// Xoa hoan toan doan nay.
// Zone config bat buoc phai co trong request.getZones().
// Validation se dam bao zones khong rong.
```

### Ly do

- `zoneId` phang khong con ton tai trong DTO moi (`zones[]` thay the).
- Hardcode default zone la sai logic khi multi-zone.
- Validation `@NotNull @Size(min=1)` tren `zones` dam bao caller phai truyen zone.

---

## Diem 2: Phase 1 - Tao nodegroup voi zonePlacementGroupMap

### Truoc thay doi (line 97-118)

```java
// Phase 1: Create all node groups sequentially
final int MAX_NODEGROUP_RETRIES = 3;
WorkflowInfo info = Workflow.getInfo();
String placementGroupId = null;
List<String> nodeGroupNames = CommonUtil.generateNodeGroupNames(
    new ArrayList<>(), userId, clusterId, request.getNumNodes());
try {
    for (String nodeGroupName : nodeGroupNames) {
        NodeGroupCreateRequestDto nodeGroupCreateRequestDto =
                nodeGroupActivities.buildCreateNodeGroupRequest(
                    nodeGroupName, placementGroupId, namespace, request);
        NodeGroupCreateResponseDto response =
                nodeGroupActivities.createNodeGroup(nodeGroupCreateRequestDto);
        nodeGroupIds.add(response.getId());
        if (placementGroupId == null) {
            placementGroupId = nodeGroupWaitActivities
                .getNodeGroupPlacementGroupId(response.getId());
        }
    }
} catch (Exception e) {
    rollbackRequest.setNodeGroupIds(nodeGroupIds);
    throw e;
}
rollbackRequest.setNodeGroupIds(nodeGroupIds);
final String finalPlacementGroupId = placementGroupId;
```

### Sau thay doi

```java
// Phase 1: Create all node groups sequentially (round-robin theo zones[])
final int MAX_NODEGROUP_RETRIES = 3;
WorkflowInfo info = Workflow.getInfo();
List<ZoneConfigDto> zones = request.getZones();
Map<String, String> zonePlacementGroupMap = new HashMap<>();
List<String> nodeGroupZones = new ArrayList<>();  // luu zoneId cua tung nodegroup (cho retry)

List<String> nodeGroupNames = CommonUtil.generateNodeGroupNames(
    new ArrayList<>(), userId, clusterId, request.getNumNodes());
try {
    for (int i = 0; i < nodeGroupNames.size(); i++) {
        ZoneConfigDto target = zones.get(i % zones.size());
        String zoneId = target.getZoneId();
        String subnetId = target.getManagementSubnetId();
        String pgId = zonePlacementGroupMap.get(zoneId);
        nodeGroupZones.add(zoneId);

        NodeGroupCreateRequestDto nodeGroupCreateRequestDto =
                nodeGroupActivities.buildCreateNodeGroupRequest(
                    nodeGroupNames.get(i), pgId, namespace, request,
                    zoneId, subnetId);
        NodeGroupCreateResponseDto response =
                nodeGroupActivities.createNodeGroup(nodeGroupCreateRequestDto);
        nodeGroupIds.add(response.getId());

        if (pgId == null) {
            String newPgId = nodeGroupWaitActivities
                .getNodeGroupPlacementGroupId(response.getId());
            zonePlacementGroupMap.put(zoneId, newPgId);
        }
    }
} catch (Exception e) {
    rollbackRequest.setNodeGroupIds(nodeGroupIds);
    throw e;
}
rollbackRequest.setNodeGroupIds(nodeGroupIds);
```

### Ly do

- `placementGroupId` don le chi hop le cho 1 zone. Multi-zone can map rieng moi zone.
- Round-robin `zones.get(i % zones.size())` phan bo deu node qua cac zone.
- `nodeGroupZones` luu lai zone moi node thuoc ve, phuc vu cho retry Phase 2.
- `buildCreateNodeGroupRequest` them 2 tham so `zoneId`, `subnetId` de activity biet tao nodegroup o zone nao.
- `finalPlacementGroupId` bi xoa vi khong con 1 PG duy nhat.

---

## Diem 3: signalParentIfPresent - them zone metadata

### Truoc thay doi (line 120-121, 216-230)

Loi goi:

```java
signalParentIfPresent(info, request.getPostgresClusterId(), nodeGroupIds, userId);
```

Method:

```java
private void signalParentIfPresent(WorkflowInfo info, String postgresClusterId,
                                   List<String> nodeGroupIds, Long userId) {
    if (info.getParentWorkflowId().isPresent() && info.getParentRunId().isPresent()) {
        WorkflowExecution parentExecution = WorkflowExecution.newBuilder()
                .setWorkflowId(info.getParentWorkflowId().get())
                .setRunId(info.getParentRunId().get())
                .build();
        Workflow.newUntypedExternalWorkflowStub(parentExecution)
                .signal("syncBackend", JsonUtil.toJson(
                    PostgresClusterSignalParentWorkflowDto.builder()
                        .postgresClusterId(postgresClusterId)
                        .nodeGroupIds(new ArrayList<>(nodeGroupIds))
                        .userId(userId)
                        .build()));
    }
}
```

### Sau thay doi

Loi goi:

```java
signalParentIfPresent(info, request.getPostgresClusterId(), nodeGroupIds,
                      nodeGroupZones, userId);
```

Method:

```java
private void signalParentIfPresent(WorkflowInfo info, String postgresClusterId,
                                   List<String> nodeGroupIds,
                                   List<String> nodeGroupZones, Long userId) {
    if (info.getParentWorkflowId().isPresent() && info.getParentRunId().isPresent()) {
        WorkflowExecution parentExecution = WorkflowExecution.newBuilder()
                .setWorkflowId(info.getParentWorkflowId().get())
                .setRunId(info.getParentRunId().get())
                .build();
        Workflow.newUntypedExternalWorkflowStub(parentExecution)
                .signal("syncBackend", JsonUtil.toJson(
                    PostgresClusterSignalParentWorkflowDto.builder()
                        .postgresClusterId(postgresClusterId)
                        .nodeGroupIds(new ArrayList<>(nodeGroupIds))
                        .nodeGroupZones(new ArrayList<>(nodeGroupZones))
                        .userId(userId)
                        .build()));
    }
}
```

DTO signal can bo sung:

```java
// PostgresClusterSignalParentWorkflowDto.java
public class PostgresClusterSignalParentWorkflowDto {
    private String postgresClusterId;
    private List<String> nodeGroupIds = new ArrayList<>();
    private List<String> nodeGroupZones = new ArrayList<>();  // NEW
    private Long userId;
}
```

### Ly do

- Parent workflow can biet nodegroup nao thuoc zone nao de luu vao DB/metadata.
- Khi retry thay the nodegroup (Phase 2), zone mapping co the thay doi, parent can cap nhat.
- `nodeGroupZones` song song voi `nodeGroupIds`: `nodeGroupZones.get(i)` la zone cua `nodeGroupIds.get(i)`.

---

## Diem 4: Phase 2 - Retry co zone awareness

### Truoc thay doi (line 123-154)

```java
// Phase 2: Wait song song, moi coroutine tu retry neu ERROR (max 3 lan)
List<Promise<Void>> inFlight = new ArrayList<>();
for (int i = 0; i < nodeGroupNames.size(); i++) {
    final int idx = i;
    final String nodeGroupName = nodeGroupNames.get(idx);
    inFlight.add(Async.procedure(() -> {
        for (int attempt = 0; attempt < MAX_NODEGROUP_RETRIES; attempt++) {
            boolean isActive = nodeGroupWaitActivities
                .waitForNodeGroupActiveOrError(nodeGroupIds.get(idx));
            if (isActive) return;
            // ERROR: xoa node group cu va tao lai
            String failedId = nodeGroupIds.get(idx);
            try { nodeGroupActivities.deleteNodeGroup(failedId); }
            catch (Exception ignored) {}
            nodeGroupWaitActivities.waitForNodeGroupDeleted(failedId);
            NodeGroupCreateRequestDto dto =
                nodeGroupActivities.buildCreateNodeGroupRequest(
                    nodeGroupName, finalPlacementGroupId, namespace, request);
            try {
                String newId = nodeGroupActivities.createNodeGroup(dto).getId();
                nodeGroupIds.set(idx, newId);
            } catch (CustomException e) {
                throw new RuntimeException(e);
            }
            signalParentIfPresent(info, request.getPostgresClusterId(),
                                  nodeGroupIds, userId);
        }
        throw new RuntimeException("Node group " + nodeGroupName
            + " failed after " + MAX_NODEGROUP_RETRIES + " retries");
    }));
}
try {
    Promise.allOf(inFlight).get();
} catch (Exception e) {
    rollbackRequest.setNodeGroupIds(nodeGroupIds);
    throw e;
}
rollbackRequest.setNodeGroupIds(nodeGroupIds);
```

### Sau thay doi

```java
// Phase 2: Wait song song, moi coroutine tu retry theo zone goc (max 3 lan)
List<Promise<Void>> inFlight = new ArrayList<>();
for (int i = 0; i < nodeGroupNames.size(); i++) {
    final int idx = i;
    final String nodeGroupName = nodeGroupNames.get(idx);
    inFlight.add(Async.procedure(() -> {
        for (int attempt = 0; attempt < MAX_NODEGROUP_RETRIES; attempt++) {
            boolean isActive = nodeGroupWaitActivities
                .waitForNodeGroupActiveOrError(nodeGroupIds.get(idx));
            if (isActive) return;

            // ERROR: xoa node group cu va tao lai DUNG ZONE GOC
            String failedId = nodeGroupIds.get(idx);
            try { nodeGroupActivities.deleteNodeGroup(failedId); }
            catch (Exception ignored) {}
            nodeGroupWaitActivities.waitForNodeGroupDeleted(failedId);

            // Lay zone goc cua nodegroup nay
            String retryZoneId = nodeGroupZones.get(idx);
            ZoneConfigDto retryZone = zones.stream()
                .filter(z -> z.getZoneId().equals(retryZoneId))
                .findFirst().orElseThrow();
            String retryPgId = zonePlacementGroupMap.get(retryZoneId);

            NodeGroupCreateRequestDto dto =
                nodeGroupActivities.buildCreateNodeGroupRequest(
                    nodeGroupName, retryPgId, namespace, request,
                    retryZoneId, retryZone.getManagementSubnetId());
            try {
                String newId = nodeGroupActivities.createNodeGroup(dto).getId();
                nodeGroupIds.set(idx, newId);
            } catch (CustomException e) {
                throw new RuntimeException(e);
            }
            signalParentIfPresent(info, request.getPostgresClusterId(),
                                  nodeGroupIds, nodeGroupZones, userId);
        }
        throw new RuntimeException("Node group " + nodeGroupName
            + " failed after " + MAX_NODEGROUP_RETRIES + " retries");
    }));
}
try {
    Promise.allOf(inFlight).get();
} catch (Exception e) {
    rollbackRequest.setNodeGroupIds(nodeGroupIds);
    throw e;
}
rollbackRequest.setNodeGroupIds(nodeGroupIds);
```

### Ly do

- Code cu dung `finalPlacementGroupId` (1 gia tri duy nhat) cho moi retry -> sai zone.
- Code moi lookup `nodeGroupZones.get(idx)` de biet nodegroup do thuoc zone nao.
- Retry tao lai nodegroup trong **dung zone goc** voi **dung placementGroupId** cua zone do.
- Dam bao sau retry, phan bo zone van dung nhu thiet ke ban dau.
- Signal parent cung truyen kem `nodeGroupZones` de parent biet mapping moi.

---

## Diem 5: LB phase - resolve subnet theo zone

### Truoc thay doi (line 163-183)

```java
Map<String, String> endpointMap = new HashMap<>();
if(request.getPoolerEnable()){
    postgresClusterActivities.applyPoolerRW(namespace,
        request.getPrivateSubnetId(), request.getPublicAccess(),
        true, request.getZoneId());
    Map<String, String> loadBalancerIdMap =
        postgresClusterLBWaitActivities.waitForPoolerRWReady(
            namespace, request.getPublicAccess(), true);
    postgresClusterActivities.applyPoolerRO(namespace,
        request.getPrivateSubnetId(), loadBalancerIdMap,
        request.getZoneId());
    endpointMap = postgresClusterLBWaitActivities.waitForAllPoolerReady(
        namespace, request.getPublicAccess(), true);
} else {
    postgresClusterActivities.applyDirectRW(namespace,
        request.getPrivateSubnetId(), request.getPublicAccess(),
        true, request.getZoneId());
    Map<String, String> loadBalancerIdMap =
        postgresClusterLBWaitActivities.waitForDirectRWReady(
            namespace, request.getPublicAccess(), true);
    postgresClusterActivities.applyDirectRO(namespace,
        request.getPrivateSubnetId(), loadBalancerIdMap,
        request.getZoneId());
    endpointMap = postgresClusterLBWaitActivities.waitForAllDirectReady(
        namespace, request.getPublicAccess(), true);
}
```

### Sau thay doi (de xuat - phu thuoc vao strategy LB multi-zone)

```java
// Lay privateSubnetId tu zone dau tien lam subnet chinh cho LB
// Hoac: truyen List<String> subnetIds cho cross-zone LB (phase sau)
String lbPrivateSubnetId = zones.get(0).getPrivateSubnetId();
String lbZoneId = zones.get(0).getZoneId();

Map<String, String> endpointMap = new HashMap<>();
if(request.getPoolerEnable()){
    postgresClusterActivities.applyPoolerRW(namespace,
        lbPrivateSubnetId, request.getPublicAccess(),
        true, lbZoneId);
    Map<String, String> loadBalancerIdMap =
        postgresClusterLBWaitActivities.waitForPoolerRWReady(
            namespace, request.getPublicAccess(), true);
    postgresClusterActivities.applyPoolerRO(namespace,
        lbPrivateSubnetId, loadBalancerIdMap, lbZoneId);
    endpointMap = postgresClusterLBWaitActivities.waitForAllPoolerReady(
        namespace, request.getPublicAccess(), true);
} else {
    postgresClusterActivities.applyDirectRW(namespace,
        lbPrivateSubnetId, request.getPublicAccess(),
        true, lbZoneId);
    Map<String, String> loadBalancerIdMap =
        postgresClusterLBWaitActivities.waitForDirectRWReady(
            namespace, request.getPublicAccess(), true);
    postgresClusterActivities.applyDirectRO(namespace,
        lbPrivateSubnetId, loadBalancerIdMap, lbZoneId);
    endpointMap = postgresClusterLBWaitActivities.waitForAllDirectReady(
        namespace, request.getPublicAccess(), true);
}
```

### Ly do

- `request.getPrivateSubnetId()` va `request.getZoneId()` khong con ton tai trong DTO moi.
- Tam thoi dung zone dau tien cho LB (single-subnet LB), tuong thich nguoc.
- Phase sau co the mo rong: truyen danh sach subnet cho cross-zone LB.
- Khong thay doi signature cua activity LB trong phase nay -> giam risk.

---

## Diem 6: Rollback - khong can thay doi logic

### Code hien tai (line 196-209)

```java
catch (Exception e) {
    if (TemporalUtils.rollbackEnabled && rollbackRequest.getPostgresClusterId() != null) {
        ChildWorkflowStub childWorkflowStub = Workflow.newUntypedChildWorkflowStub(
            "DeletePostgresClusterBackendWorkflow",
            ChildWorkflowOptions.newBuilder()
                .setRetryOptions(NO_RETRY)
                .setTaskQueue(TemporalUtils.taskQueue)
                .setWorkflowTaskTimeout(Duration.ofMinutes(1))
                .setWorkflowId(Workflow.getInfo().getWorkflowId() + "-cluster-rollback")
                .build());
        childWorkflowStub.execute(Boolean.class, userId, new Gson().toJson(rollbackRequest));
    }
    logEntity.reason(e.getMessage());
    logEntity.exp(e).fail();
    throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), e.getClass().getName());
}
```

### Sau thay doi

**Khong can thay doi logic rollback.**

### Ly do

- `DeletePostgresClusterReqDto` chi can `postgresClusterId` va `nodeGroupIds`.
- Rollback xoa theo ID, khong phu thuoc vao zone.
- `rollbackRequest.setNodeGroupIds(nodeGroupIds)` duoc cap nhat dung tai moi diem trong workflow (sau Phase 1 create, sau Phase 2 retry).
- Delete workflow xoa toan bo nodegroup bat ke zone -> khong can thay doi.
- **Luu y:** Neu sau nay muon rollback tung phan (chi xoa nodegroup loi), thi moi can bo sung zone info vao rollback request.

---

## Tong ket cac diem thay doi trong file


| #   | Khu vuc                                    | Thay doi                                                                                | Muc do    |
| --- | ------------------------------------------ | --------------------------------------------------------------------------------------- | --------- |
| 1   | Default zoneId (L72-74)                    | Xoa hardcode `HCM03-1A`                                                                 | Xoa code  |
| 2   | Phase 1 create (L97-118)                   | `placementGroupId` -> `zonePlacementGroupMap`, round-robin zones, them `nodeGroupZones` | Sua lon   |
| 3   | signalParentIfPresent (L120-121, L216-230) | Them `nodeGroupZones` param, cap nhat DTO signal                                        | Sua vua   |
| 4   | Phase 2 retry (L123-154)                   | Retry theo zone goc, dung `zonePlacementGroupMap` thay `finalPlacementGroupId`          | Sua lon   |
| 5   | LB phase (L163-183)                        | Thay `request.getZoneId()`/`getPrivateSubnetId()` bang resolve tu `zones[0]`            | Sua nhe   |
| 6   | Rollback (L196-209)                        | Khong thay doi                                                                          | Khong sua |


---

## Cac file lien quan can sua dong bo


| File                                          | Thay doi                                                                                      |
| --------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `CreatePostgresClusterReqDto.java`            | Xoa `zoneId`, `privateSubnetId`, `managementSubnetId` phang. Them `List<ZoneConfigDto> zones` |
| `ZoneConfigDto.java`                          | Tao moi: `zoneId`, `managementSubnetId`, `privateSubnetId`                                    |
| `PostgresClusterSignalParentWorkflowDto.java` | Them `List<String> nodeGroupZones`                                                            |
| `NodeGroupActivities.java`                    | Them 2 param `zoneId`, `subnetId` vao `buildCreateNodeGroupRequest`                           |
| `NodeGroupActivitiesImpl.java`                | Cap nhat implementation tuong ung                                                             |


