# Multi-Zone Impact Overview

Tai lieu nay duoc viet lai theo format checklist: moi dong impact gom `Kien giai` va `Cau hoi dieu chinh`.

## 1) Input contract tu Temporal

- **Impact:** Input hien tai dang theo single-zone (`zoneId`, `managementSubnetId`, `privateSubnetId`).
  - **Kien giai:** Khi so zone tang, model phang se phat sinh nhieu field hard-code va kho trace quan he zone-subnet.
  - **Cau hoi dieu chinh:** Chot thoi diem chuyen sang schema `zones[]` trong payload la khi nao?
- **Impact:** Nguon su that cho zone/subnet neu dat o config se xung dot voi input runtime.
  - **Kien giai:** Workflow nhan lenh tu Temporal thi du lieu zone/subnet nen di kem request de de audit va replay.
  - **Cau hoi dieu chinh:** Co thong nhat nguyen tac "request la source-of-truth" cho create/scale khong?
- **Impact:** Backward compatibility voi caller cu can duoc giu trong giai doan chuyen tiep.
  - **Kien giai:** Neu ep doi schema ngay se tang rui ro rollout va tang coordination cost.
  - **Cau hoi dieu chinh:** Co cho phep che do transitional (uu tien `zones[]`, fallback input cu) khong?

## 2) Create workflow (nodegroup provisioning)

- **Impact:** Logic tao nodegroup hien co co xu huong co dinh 1 subnet.
  - **Kien giai:** Da-zone can subnet theo tung zone de nodegroup nam dung fault domain.
  - **Cau hoi dieu chinh:** Co bat buoc round-robin zone cho moi nodegroup tao moi khong?
- **Impact:** Retry phase cua create neu khong giu context zone se tao lai sai placement.
  - **Kien giai:** Retry can su dung lai zone/subnet goc de khong pha vo phan bo fault domain da dat.
  - **Cau hoi dieu chinh:** Co can luu `nodeGroupName -> zoneConfig` trong workflow state de retry an toan khong?

## 3) Scale workflow (dac biet scale-out)

- **Impact:** Scale-out hien tai de bi bias theo zone cua node dau tien.
  - **Kien giai:** Neu node moi tiep tuc vao mot zone, muc tieu HA multi-zone bi suy giam.
  - **Cau hoi dieu chinh:** Rule can bang zone khi scale-out la round-robin co dinh hay theo weighting?
- **Impact:** Scale-in co the lam mat can bang zone neu khong co guardrail.
  - **Kien giai:** He thong co the vo tinh xoa node trong cung mot zone, dan den mat da dang fault domain.
  - **Cau hoi dieu chinh:** Co can dinh nghia minimum nodes per zone cho scale-in khong?

## 4) Placement group strategy

- **Impact:** Dung mot placement group chung cho nhieu zone co the khong phu hop rang buoc cloud.
  - **Kien giai:** Nhieu provider rang buoc placement group theo fault domain, de phat sinh loi tao node neu dung sai.
  - **Cau hoi dieu chinh:** Co xac nhan chinh sach chinh thuc "1 placement group / zone" khong?
- **Impact:** Ten va vong doi placement group can on dinh de rollback/delete khong loi.
  - **Kien giai:** Naming khong ro rang se kho thu gom placement groups theo cluster va zone.
  - **Cau hoi dieu chinh:** Co thong nhat convention ten theo `namespace-zoneId` khong?

## 5) Scheduling / CRD

- **Impact:** Da tao nodegroup da-zone nhung pod van co the gom ve 1 zone.
  - **Kien giai:** Khong co `topologySpreadConstraints` thi scheduler khong bi ep phan bo theo zone.
  - **Cau hoi dieu chinh:** Co dua spread constraints vao default CRD cho cluster multi-zone khong?
- **Impact:** Chinh sach `whenUnsatisfiable` anh huong truc tiep den hanh vi van hanh.
  - **Kien giai:** `DoNotSchedule` giu dung topology nhung co the lam pending; `ScheduleAnyway` de chay nhung co the lech zone.
  - **Cau hoi dieu chinh:** Team uu tien availability hay topology strict trong phase dau?

## 6) Validation va governance

- **Impact:** Validation DTO dang theo mot zone co dinh se can tro mo rong.
  - **Kien giai:** Pattern/static list cho 1 field zone khong con phu hop voi cau truc zone list.
  - **Cau hoi dieu chinh:** Validation zone nen dua theo dynamic source (catalog/API) hay static allowlist?
- **Impact:** Kiem tra dau vao can bao ve tinh hop le cua zone-subnet pair.
  - **Kien giai:** Neu zone/subnet mismatch, workflow se fail o sau va ton chi phi retry.
  - **Cau hoi dieu chinh:** Co can preflight validate toan bo `zones[]` truoc khi tao tai nguyen khong?

## 7) Testing va acceptance

- **Impact:** Test case single-zone khong du de chung minh multi-zone readiness.
  - **Kien giai:** Can test create, scale, failover trong boi canh mat AZ de xac thuc muc tieu HA.
  - **Cau hoi dieu chinh:** Tieu chi pass/fail cho bai test mat 1 AZ duoc chot nhu the nao (RTO/RPO/availability)?
- **Impact:** Can theo doi do lech phan bo pod va node theo zone sau moi thao tac.
  - **Kien giai:** Khong co metric/checklist ro rang thi kho phat hien "multi-zone danh nghia nhung single-zone thuc te".
  - **Cau hoi dieu chinh:** Co can bo sung gate sau workflow (assert spread >= 2 zones) khong?

## 8) Backward compatibility va rollout

- **Impact:** Doi contract input anh huong den he thong goi workflow tu upstream.
  - **Kien giai:** Rollout khong co lo trinh se tao mismatch giua version caller va worker.
  - **Cau hoi dieu chinh:** Chot cach rollout theo feature-flag, versioned DTO hay migration theo moi truong?
- **Impact:** Replay/trace cua Temporal can nhat quan du lieu dau vao giua cac lan retry.
  - **Kien giai:** Khac biet giua config runtime va request payload co the lam kho debug workflow history.
  - **Cau hoi dieu chinh:** Co thong nhat chi doc zone/subnet tu request de dam bao determinism khong?

## 9) Deferred - Load Balancer (ngoai scope hien tai)

- **Impact:** Hanh vi traffic cross-zone cua LB chua duoc dieu chinh o phase nay.
  - **Kien giai:** Neu xu ly LB qua som khi core provisioning chua on se tang scope va tang rui ro.
  - **Cau hoi dieu chinh:** Team co dong y de LB o backlog phase sau sau khi on dinh create/scale multi-zone khong?
- **Impact:** Annotation/policy LB co the anh huong latency va chi phi lien-AZ.
  - **Kien giai:** Can danh gia sau tren he thong da co topology da-zone thuc te.
  - **Cau hoi dieu chinh:** Khi vao phase LB, metric uu tien la availability, latency hay cost?

---

## Tong hop quyet dinh can chot truoc khi code

1. Chot schema `zones[]` va lo trinh backward compatibility.
2. Chot rule can bang zone cho create/scale-out.
3. Chot strategy placement group theo zone.
4. Chot policy scheduling spread (`DoNotSchedule` vs `ScheduleAnyway`).
5. Chot bo acceptance test failover mat AZ.
6. Xac nhan LB la deferred scope cho phase sau.