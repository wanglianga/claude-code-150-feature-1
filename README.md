# 工地临时材料领用退库损耗与班组核算平台

面向项目部的材料全链路管理平台：把 **采购入库 → 质检 → 预算 → 领料 → 退库 → 损耗 → 变更签证 → 盘点 → 班组月度核算 → 财务结算/索赔** 放在同一条材料链路上，按楼栋、施工区域、工序、图纸和班组串接。

技术栈：**React 18 + Vite**（前端）、**Spring Boot 3 + Spring Security(JWT) + Spring Data JPA**（后端）、**MySQL 8**（生产库，Compose 部署）、H2（本地零依赖演示库）。

## 一、功能总览

| 角色 | 账号 | 能做什么 |
| --- | --- | --- |
| 项目经理 PM | `pm / pm123` | 查看某楼栋/工序/班组的真实材料消耗与偏差、变更签证确认、核算与财务总览 |
| 仓管/材料员 | `keeper / keeper123` | 入库登记与批次质检、发料核对、退库确认、损耗核销、盘点调账、损耗月底复盘 |
| 预算员 | `budget / budget123` | 编制预算、用实际消耗反推后续采购计划与分包结算量、确认签证 |
| 财务 | `finance / finance123` | 供应商结算、班组扣款、变更索赔申报、月度核算确认 |
| 班组长 | `foreman1..5 / foreman123` | 按楼栋/工序/图纸申请领料（含夜间紧急）、办理退库、查看本班组核算 |

覆盖的业务场景：临时追加领料、**规格领错退换（未使用退回 + 已切割损耗 + 现场照片 + 责任判定）**、施工变更退库、瓷砖破损、砂浆过期、夜间紧急领料（先发后补）、供应商补货延迟/质量不合格、盘点账实不符。

核心口径：

- 批次现场库存 = 入库量 − 已领量 + 退库量 − 损耗量 + 盘点调整量；质检不合格/待检批次不可发料。
- 真实（净）消耗 = 已发料金额 − 完好退库金额；损坏退库不回补库存，计入班组责任损耗。
- **规格领错退换**：班组长上传未使用数量、已切割数量与现场照片发起退换；仓管确认时判定责任 ——
  未使用部分重新入库；已切割部分自动生成 CUT 切割损耗单。
  **仓管发错（WAREHOUSE）→ 损耗项目承担，班组不扣款；班组申请错误（CREW）→ 成本归入该班组。**
  退换完成后更新可用库存与班组成本；同班组再次申请相同材料时，申请页与发料核对页均提示"曾经领错规格"。
- 月底班组核算：预算金额、实际领用、退库、损耗按责任归属（班组/供应商/设计变更/项目承担/不可抗力）拆分；
  **班组扣款 = 班组责任损耗（含损坏退库、班组责任的切割损耗）+ 正偏差（净消耗−预算的超耗部分）**；供应商质量损耗单列索赔，不扣班组。

## 二、一键启动（Docker Compose，推荐）

前置：安装 Docker / Docker Compose。

```bash
cd 150-b
docker compose up -d --build
```

- 前端：http://localhost （Nginx 容器，80 端口；如被占用可改 docker-compose.yml 的端口映射）
- 后端：http://localhost:8080
- MySQL：localhost:3306（库名 `material`，用户 `material/material`，root 密码 `root`）

首次启动后端会自动建表并写入覆盖全部场景的演示数据。停止：`docker compose down`；连数据一起清除：`docker compose down -v`。

## 三、本地开发启动

### 后端（默认 H2 内存库，无需安装数据库）

```bash
cd backend
mvn spring-boot:run
# 或：mvn package && java -jar target/material-platform.jar
```

- 服务地址 http://localhost:8080 ，H2 控制台 http://localhost:8080/h2-console
  （JDBC URL：`jdbc:h2:mem:materialdb`，用户名 `sa`，密码空）
- 切换 MySQL：以 `mysql` profile 启动并提供环境变量（见 `backend/src/main/resources/application-mysql.yml`）：

```bash
SPRING_PROFILES_ACTIVE=mysql \
MYSQL_HOST=localhost MYSQL_DB=material MYSQL_USER=root MYSQL_PASSWORD=root \
mvn spring-boot:run
```

### 前端

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173 ，/api 已代理到 http://localhost:8080
npm run build    # 产出 dist/，可用 npm run preview 预览
```

> 若 npm 全局缓存目录有权限问题，可指定临时缓存：`npm_config_cache=/tmp/npm-cache npm install`

## 四、推荐验证路径（登录后按角色体验）

1. **仓管 keeper**：「现场库存总览」看库存价值/预警 →「入库批次与质检」把待检批次判合格（补货批次可判不合格）。
2. **班组长 foreman5（水电班孙明）**：「领料单」可见一张"夜间紧急"单；点「申请领料」选择 PVC穿线管，页面即提示**该班组曾领错规格**（来自历史退换记录）。
3. **规格领错退换主线**：
   - 班组长 foreman5：「退库单 → 办理退库」原因选"规格领错"，填写**未使用数量 / 已切割数量**并上传**现场照片**后提交（不传照片会被拦截）。
   - 仓管 keeper：「退库单」对规格领错单点「确认入库」，在弹窗中**判定责任**（仓管发错=班组不扣款 / 班组申请错误=成本归班组）；确认后未使用部分回补批次库存，已切割部分自动生成 CUT 损耗单（单据详情可见损耗单号与责任）。
   - 种子数据已含一张已办结退换单 `TK20261003001`（班组申请错误，切割 20 米计损耗 70 元）与一张待确认单 `TK20261004002`，可直接体验确认流程。
4. **仓管 keeper**：「领料单 → 核对发料」核对预算量/已领量/现场库存，选择批次、填实发数量后发料（超预算/库存不足会拦截或预警；曾领错规格的材料行内会显示提示角标）；「损耗登记」登记瓷砖破损（班组）、砂浆过期（班组）、供应商质量问题；「盘点与账实调整」确认盘点单调账；「损耗月底复盘」查看按设计变更/施工错误（含切割损耗）/保管不当/供应商质量归类的损耗结构。
5. **项目经理 pm**：「楼栋消耗与偏差」按 1#楼/工序/班组过滤查看真实消耗与超耗；「变更签证」查看并确认设计变更签证。
6. **预算员 budget**：「采购计划反推」查看建议采购量、补货延迟风险与分包结算复核口径。
7. **财务 finance**：「班组月度核算」选 2026-10 一键生成全部班组核算单并确认（水电班核算中：班组责任的切割损耗 70 元计入扣款，仓管发错的切割损耗归项目承担不扣班组）；「财务结算与索赔」查看供应商应付（扣不合格批次）、班组扣款、签证索赔。

## 五、主要 HTTP 接口（JWT：`Authorization: Bearer <token>`）

| 模块 | 接口 |
| --- | --- |
| 认证 | `POST /api/auth/login` |
| 元数据 | `GET /api/meta/options`（材料/供应商/班组/楼栋/工序下拉） |
| 库存/批次 | `GET /api/inventory`、`GET /api/batches`、`POST /api/batches/inbound`、`PUT /api/batches/{id}/qc` |
| 预算 | `GET/POST /api/budgets` |
| 领料 | `GET /api/requisitions`、`POST /api/requisitions`、`GET /api/requisitions/{id}/precheck`、`PUT /api/requisitions/{id}/issue`、`PUT /api/requisitions/{id}/reject`、`GET /api/requisitions/spec-wrong-alerts?crewId=&materialId=`（领错规格提示） |
| 退库/退换 | `GET/POST /api/returns`、`GET /api/returns/{id}`、`PUT /api/returns/{id}/confirm`（规格领错需在 body 传 `faultType`: WAREHOUSE/CREW）、`PUT /api/returns/{id}/reject` |
| 损耗 | `GET/POST /api/wastes` |
| 变更签证 | `GET/POST /api/visas`、`PUT /api/visas/{id}/confirm`、`PUT /api/visas/{id}/claim` |
| 盘点 | `GET/POST /api/stock-checks`、`PUT /api/stock-checks/{id}/adjust` |
| 月度核算 | `GET /api/settlements`、`POST /api/settlements/generate`、`PUT /api/settlements/{id}/confirm` |
| 分析 | `GET /api/analytics/consumption`、`/waste-review`、`/procurement-plan`、`/finance` |

curl 示例：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"keeper","password":"keeper123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s http://localhost:8080/api/inventory -H "Authorization: Bearer $TOKEN"
```

## 六、测试

```bash
cd backend
mvn test        # 全链路集成测试（H2 + 种子数据，见 MaterialFlowIntegrationTest）
```

测试覆盖：登录鉴权与角色权限（401/403）、发料前预算/已领/库存核对、库存不足拦截、夜间紧急发料、**规格领错退换全链路（无照片拦截、未判责拦截、完好回补、已切割自动生成 CUT 损耗、仓管发错项目承担/班组申请错误归班组、库存联动、再次申请领错提示）**、盘点调账、月底核算金额口径（领用 4460、退库 665、净消耗 3795、偏差 −6805、班组扣款 70）、损耗复盘四类原因、供应商质量索赔、采购计划反推。

## 七、工程结构

```
150-b/
├── docker-compose.yml         # mysql + backend(spring) + frontend(nginx)
├── backend/                   # Spring Boot 3 / Java 17
│   ├── Dockerfile
│   └── src/main/java/com/site/material/
│       ├── domain/            # 14 个实体（批次/预算/领料/退库/退换照片/损耗/签证/盘点/核算…）
│       ├── repo/ service/ controller/
│       ├── security/          # JWT + 五种角色
│       └── config/DataInitializer.java   # 演示种子数据（全部场景）
└── frontend/                  # React 18 + Vite
    ├── Dockerfile nginx.conf
    └── src/pages/             # 14 个角色化页面
```

## 八、已执行的验证结论

- 后端 `mvn package` 编译通过；`mvn test` 集成测试 **1 个用例、全部通过**（H2 内存库），覆盖规格领错退换全链路断言（照片必传、责任必判、回补/损耗/扣款归属、领错提示）。
- 后端 jar 以默认 H2 配置实际启动成功，并经 curl 实测：登录、领料 precheck/issue、库存、**退换单创建（无照片 400）→ 确认（未判责 400 → 判仓管发错 200 且损耗项目承担）→ 批次退库/损耗联动 → spec-wrong-alerts 提示 → precheck 行内提示 → 月度核算（班组扣款 70、项目承担 52.5）** 均返回正常。
- 前端 `npm run build`（Vite）构建成功，产出 dist 静态资源。
- Docker Compose 配置已按多阶段构建编写；当前沙箱环境无 Docker 守护进程，未在本机实际执行 `docker compose up`，请在有 Docker 的机器上按第二节命令验证。
