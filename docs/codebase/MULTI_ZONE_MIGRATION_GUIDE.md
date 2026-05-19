# MULTI_ZONE_MIGRATION_GUIDE

## 1. Muc tieu migration

Tai lieu nay dinh huong migration theo 3 nguyen tac:

1. Dung `zones[]` la contract chinh de van hanh ca 1-zone va multi-zone.
2. Quan ly placement group theo zone qua `zonePlacementGroupMap`.
3. Lam day du scheduling/CRD de multi-zone dat duoc HA thuc te.

---

## 2. Baseline hien trang

### Truoc thay doi

- Input nghieng ve field phang (`zoneId`, `managementSubnetId`, ...).
- Create/scale de co xu huong dung 1 placement group trung tam.
- Scheduling chua du constraints de bao dam trai deu pod theo zone.

### Sau thay doi (muc tieu)

- Input thong nhat qua `zones[]`.
- Create/scale co `Map<String, String> zonePlacementGroupMap`.
- CRD bo sung spread + anti-affinity de phan bo pod theo zone.

### Vi sao phai thay doi

- Neu chi doi nodegroup ma khong doi scheduling, pod van co the dồn 1 zone.
- Neu khong map placement theo zone, create/scale co the sai fault domain.

---

## 3. Contract 1-zone va multi-zone

### Truoc thay doi

- Single-zone la duong chinh; multi-zone can them nhieu quy tac ngầm.

### Sau thay doi

- 1-zone: `zones[]` co 1 phan tu.
- multi-zone: `zones[]` co nhieu phan tu.
- Workflow dung chung 1 code path round-robin theo `zones[]`.

### Vi sao phai thay doi

- Mot contract duy nhat giup test/replay/oncall de hon.
- Khong can dua map subnet vao config refer ben ngoai.

---

## 4. Delta ky thuat - PlacementGroup map theo zone

### 4.1 Create workflow

#### Truoc thay doi

- Dung 1 `placementGroupId` chung trong vong tao nodegroup.
- Retry co the tao lai nodegroup voi placementGroup khong khop zone.

#### Sau thay doi

- Dung `zonePlacementGroupMap`.
- Round-robin qua `zones[]`, moi zone tu quan ly PG rieng:
  - Chua co PG thi tao NEW.
  - Co PG thi dung EXISTING.
- Retry lookup map theo `retryZone`.

Pseudo-code:

```java
Map<String, String> zonePlacementGroupMap = new HashMap<>();
for (int i = 0; i < nodeGroupNames.size(); i++) {
    ZoneConfigDto target = zones.get(i % zones.size());
    String zoneId = target.getZoneId();
    String pgId = zonePlacementGroupMap.get(zoneId);
    // create nodegroup
    if (pgId == null) {
        zonePlacementGroupMap.put(zoneId, fetchedPgId);
    }
}
```

#### Vi sao phai thay doi

- Placement group bi rang buoc fault domain.
- Can toi uu de nodegroup trong cung zone dung dung PG cua zone do.

### 4.2 Scale-out workflow

#### Truoc thay doi

- Co xu huong lay PG tu node dau tien va tai su dung cho node moi.

#### Sau thay doi

- Build map tu nodegroup hien huu.
- Chon zone round-robin voi `existingCount` offset.
- Tao node moi theo zone/subnet/PG tu map.
- Neu zone moi chua co PG thi tao va bo sung map.

Pseudo-code:

```java
Map<String, String> zonePlacementGroupMap = buildFromExisting(nodeGroupDetails);
for (int i = 0; i < nodeGroupNamesNew.size(); i++) {
    ZoneConfigDto target = zones.get((existingCount + i) % zones.size());
    String zoneId = target.getZoneId();
    String pgId = zonePlacementGroupMap.get(zoneId);
    // create and update map if needed
}
```

#### Vi sao phai thay doi

- Giu can bang zone khi scale-out.
- Tranh cluster tiep tuc nghieng ve zone da co.

---

## 5. Delta ky thuat - Scheduling / CRD day du

### 5.1 topologySpreadConstraints

#### Truoc thay doi

- Chua rang buoc spread theo zone.

#### Sau thay doi

- Bo sung:
  - `topologyKey: topology.kubernetes.io/zone`
  - `maxSkew: 1`
  - `labelSelector` theo nhan cluster.

#### Vi sao phai thay doi

- Ep scheduler giu pod phan bo fault-domain aware.

### 5.2 whenUnsatisfiable

#### Truoc thay doi

- Chua co policy ro rang cho truong hop thieu node theo zone.

#### Sau thay doi

- Chon policy theo moi truong:
  - `ScheduleAnyway`: uu tien availability.
  - `DoNotSchedule`: uu tien strict spread.

#### Vi sao phai thay doi

- Tranh xung dot uu tien giua SLO availability va spread strict.

### 5.3 podAntiAffinity

#### Truoc thay doi

- Chua co lop phong ve bo sung giam co-location.

#### Sau thay doi

- Khuyen nghi bo sung anti-affinity theo nhan `application`/`cluster-name`.

#### Vi sao phai thay doi

- Giam rui ro pod quan trong dat gan nhau trong cung topology.

### 5.4 Postgresql.java va additionalPodSpec

#### Truoc thay doi

- Model co the chua map day du scheduling fields khi inject bang code.

#### Sau thay doi

- Mo rong model ho tro `additionalPodSpec`.

#### Vi sao phai thay doi

- Dam bao object scheduling di xuyen suot tu code -> CRD.

### 5.5 Hanh vi 1-zone va multi-zone

#### Truoc thay doi

- Chua mo ta ro hanh vi scheduling theo tung mode.

#### Sau thay doi

- 1-zone: van chay binh thuong voi `zones[]` 1 phan tu.
- multi-zone: spread + anti-affinity la can thiet de dat HA.

#### Vi sao phai thay doi

- Tranh hieu nham khi rollout theo tung moi truong.

---

## 6. Before / After / Why tong hop nhanh

| Hang muc | Truoc | Sau | Vi sao |
| --- | --- | --- | --- |
| Input contract | Field phang, de single-zone bias | `zones[]` duy nhat cho ca 1-zone/multi-zone | Don gian hoa logic va mo rong |
| Create PG | 1 PG trung tam | Map `zone -> placementGroupId` | Dung fault domain theo zone |
| Scale PG | De lay PG tu node dau | Build/maintain map per-zone khi scale-out | Giu can bang zone |
| Scheduling | Chua spread ro rang | Spread + anti-affinity + policy whenUnsatisfiable | Dat HA thuc te |
| CRD model | Chua chac map du | Ho tro `additionalPodSpec` | Tranh mat setting khi apply |

---

## 7. Acceptance checklist

1. Tao cluster voi `zones[]` 1 phan tu thanh cong.
2. Tao cluster voi `zones[]` nhieu phan tu va nodegroup trai deu theo round-robin.
3. Moi zone su dung dung placementGroupId theo map.
4. Scale-out khong lech ve zone cua node dau tien.
5. Pod placement quan sat duoc spread qua zone theo policy da chon.

---

## 8. Deferred scope

### Load Balancer

LB khong doi trong phase nay; chi giu note de danh gia sau khi core provisioning + scheduling on dinh.
Dưới đây là nội dung file của bạn đã được thêm dấu đầy đủ, đảm bảo giữ nguyên ý nghĩa và cấu trúc kỹ thuật:

# MULTI_ZONE_MIGRATION_GUIDE

## 1. Mục tiêu migration

Hướng migration được chốt cho phase này:

- Dùng input Temporal làm nguồn sự thật cho thông tin zone/subnet.
- Không khóa chặt thiết kế vào `PostgresClusterConfigRefer` map `zone -> subnet`.
- Chuẩn bị migration theo lộ trình, không ép toàn bộ caller đổi schema ngay lập tức.

---

## 2. Hiện trạng và giới hạn

Input mẫu hiện tại (`format_input_CreatePostgresClusterReqDto.json`) mang:

- `zoneId`
- `managementSubnetId`
- `privateSubnetId`

Hiện trạng này đủ cho single-zone, nhưng gặp giới hạn khi cần mở rộng:

- Nhiều zone cần subnet riêng cho từng zone.
- Nếu tiếp tục theo field phẳng (flat fields), schema sẽ khó bảo trì.
- Workflow khó biểu diễn rõ ý đồ tạo nodegroup theo fault domain.

---

## 3. Định hướng schema request multi-zone

### 3.1. Đề xuất model payload

```json
{
  "zones": [
    {
      "zoneId": "HCM03-1A",
      "managementSubnetId": "subnet-a",
      "privateSubnetId": "subnet-pa"
    },
    {
      "zoneId": "HCM03-1B",
      "managementSubnetId": "subnet-b",
      "privateSubnetId": "subnet-pb"
    }
  ]
}

```

### 3.2. Nguyên tắc

- Zone config đi kèm request để workflow xử lý trực tiếp.
- Mỗi zone tự khai báo subnet liên quan, không phụ thuộc map cấu hình bên ngoài.
- Input có thể mở rộng tương lai (weight, role, priority) mà không vỡ schema cũ.

---

## 4. Vị trí cần điều chỉnh code (mục tiêu kỹ thuật)

## 4.1. `CreatePostgresCluster` workflow

Workflow create cần đọc danh sách zone từ request và tạo nodegroup theo round-robin:

```java
List<ZoneConfig> zones = request.getZones();
for (int i = 0; i < nodeGroupNames.size(); i++) {
    ZoneConfig target = zones.get(i % zones.size());
    String zoneId = target.getZoneId();
    String subnetId = target.getManagementSubnetId();
    // tao nodegroup theo zone/subnet
}

```

Key point:

- Không còn logic "1 subnet dùng chung cho tất cả nodegroup".
- Placement group được quản lý theo zone trong quá trình tạo.

## 4.2. `NodeGroupActivities` và implementation

Cần mở rộng method signature để nhận dữ liệu per-zone:

```java
NodeGroupCreateRequestDto buildCreateNodeGroupRequest(
    String nodeGroupName,
    String placementGroupId,
    String namespace,
    CreatePostgresClusterReqDto request,
    String zoneId,
    String subnetId
)

```

Khi đó `NodeGroupActivitiesImpl` sử dụng:

- `zoneId` cho labels/placement strategy.
- `subnetId` từ zone config của request.

## 4.3. `ScalePostgresCluster` workflow

Scale-out cần giữ cùng nguyên tắc:

- Chọn zone theo round-robin để giữ cân bằng fault domain.
- Lấy subnet từ request zone config.
- Tái sử dụng placement group theo zone nếu đã có.

---

## 5. Scheduling và CRD (để đảm bảo phân bổ pod)

Đa-zone nodegroup chưa đủ, vì pod vẫn có thể gom vào một zone nếu scheduler không bị ràng buộc.

Cần bổ sung trong spec:

- `topologySpreadConstraints` theo `topology.kubernetes.io/zone`.
- (nếu cần) anti-affinity theo nhãn cluster.

Mục đích:

- Tăng khả năng failover AZ-level.
- Giữ tính cân bằng pod placement khi scale.

---

## 6. Migration path đề xuất

### Giai đoạn 1 - Transitional compatibility

- Chấp nhận input hiện tại (`zoneId`, `managementSubnetId`, `privateSubnetId`).
- Bổ sung đường đọc input mới nếu caller đã gửi `zones[]`.
- Ưu tiên sử dụng `zones[]` khi có.

### Giai đoạn 2 - Chuẩn hóa contract

- Temporal payload chuyển đầy đủ sang `zones[]`.
- Giảm dần và loại bỏ assumption single-zone trong workflow.
- Hoàn thiện test matrix multi-zone cho create/scale/failover.

---

## 7. Acceptance checklist cho migration

Cần xác nhận ít nhất:

1. Create cluster 3 nodes trên >= 2 zones thành công.
2. Nodegroup được tạo đúng subnet theo từng zone.
3. Pod placement không bị dồn về 1 zone duy nhất.
4. Scale-out không làm lệch về zone của node đầu tiên.
5. Mất 1 AZ vẫn có replica sống và failover đạt mục tiêu.

---

## 8. Out of scope hiện tại

### Load Balancer (deferred)

Vòng này **chưa thay đổi** phần Load Balancer.

Chỉ cần lưu ý cho phase sau:

- Có cần bỏ annotation prefer-zone hay không.
- Cross-zone routing policy trong sự cố AZ.
- Tác động đến độ trễ và chi phí liên-AZ.

Phần này chỉ ghi chú để quản lý scope, không đưa vào thay đổi code hiện tại.