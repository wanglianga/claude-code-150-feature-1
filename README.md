# 工地临时材料领用退库损耗与班组核算平台

面向项目部的材料全链路管理平台：把 **采购入库 → 质检 → 预算 → 领料 → 退库 → 损耗 → 变更签证 → 盘点 → 班组月度核算 → 财务结算/索赔** 放在同一条材料链路上，按楼栋、施工区域、工序、图纸和班组串接。

技术栈：**React 18 + Vite**（前端）、**Spring Boot 3 + Spring Security(JWT) + Spring Data JPA**（后端）、**MySQL 8**（生产库，Compose 部署）、H2（本地零依赖演示库）。

## 一、功能总览

| 角色 | 账号 | 能做什么 |
| --- | --- | --- |
| 项目经理 PM | `pm / pm123` | 查看某楼栋/工序/班组的真实材料消耗与偏差、变更签证确认、核算与财务总览 |
| 仓管/材料员 | `keeper / keeper123` | 入库登记与批次质检、发料核对、退库确认、规格退换责任判定、损耗核销、盘点调账、损耗月底复盘 |
| 预算员 | `budget / budget123` | 编制预算、用实际消耗反推后续采购计划与分包结算量、确认签证 |
| 财务 | `finance / finance123` | 供应商结算、班组扣款、变更索赔申报、月度核算确认 |
| 班组长 | `foreman1..5 / foreman123` | 按楼栋/工序/图纸申请领料（含夜间紧急）、办理退库、发起规格领错退换（上传数量与现场照片）、查看本班组核算 |

覆盖的业务场景：临时追加领料、**规格领错退换料（未使用回库＋已切割计损耗＋责任判定＋再次申请预警）**、施工变更退库、瓷砖破损、砂浆过期、夜间紧急领料（先发后补）、供应商补货延迟/质量不合格、盘点账实不符。

核心口径：

- 批次现场库存 = 入库量 − 已领量 + 退库量 − 损耗量 + 盘点调整量；质检不合格/待检批次不可发料。
- 真实（净）消耗 = 已发料金额 − 完好退库金额（含规格退换的未使用回库部分）；损坏退库不回补库存，计入班组责任损耗。
- **规格领错退换料**：班组长发起退换并上报未使用数量、已切割数量和现场照片；仓管确认后未使用部分回补原批次可用库存，已切割部分生成 `SPEC_CUT` 损耗——仓管发错（KEEPER）计项目承担、班组不扣款，班组申请错误（CREW）切割成本归入该班组月度核算；同班组再次申请相同材料时平台提示曾领错规格，仓管发料核对页同步预警。
- 月底班组核算：预算金额、实际领用、退库、损耗按责任归属（班组/供应商/设计变更/项目承担/不可抗力）拆分；
  **班组扣款 = 班组责任损耗（含损坏退库、班组申请错误的切割损耗）+ 正偏差（净消耗−预算的超耗部分）**；供应商质量损耗单列索赔，不扣班组。

## 二、一键启动（Docker Compose，推荐）

前置：安装 Docker / Docker Compose。

```bash
cd 150-feature-1-a
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
2. **班组长 foreman5（水电班孙明）**：「领料单」可见一张"夜间紧急"单；也可新建领料申请。「规格领错退换」可见一张待仓管确认的 DN25 退换单。
3. **仓管 keeper**：「领料单 → 核对发料」核对预算量/已领量/现场库存，选择批次、填实发数量后发料（超预算/库存不足会拦截或预警，有领错史的材料会红色提示"该班组曾领错此规格"）；再到「退库单」确认规格领错/变更退库（完好回补库存）与损坏退库（班组责任）。
4. **仓管 keeper**：「规格领错退换」打开待确认的 DN25 退换单，查看班组长上传的现场照片与未使用/已切割数量，判定责任：
   - 选 **班组申请错误** → 未使用 40 米重新入库（DN25 批次 1950→1990），已切割 10 米（42 元）计入该班组责任损耗，月底核算扣款；
   - 选 **仓管发错** → 库存同样回补，但切割损耗计项目承担，班组不扣款。
   随后用 foreman5 再申请一张 DN20 线管领料，可看到"该班组曾领错此材料规格"提示。
5. **仓管 keeper**：「损耗登记」登记瓷砖破损（班组）、砂浆过期（班组）、供应商质量问题；「盘点与账实调整」确认盘点单调账；「损耗月底复盘」查看按规格领错/设计变更/施工错误/保管不当/供应商质量归类的损耗结构。
6. **项目经理 pm**：「楼栋消耗与偏差」按 1#楼/工序/班组过滤查看真实消耗与超耗；「变更签证」查看并确认设计变更签证。
7. **预算员 budget**：「采购计划反推」查看建议采购量、补货延迟风险与分包结算复核口径。
8. **财务 finance**：「班组月度核算」选 2026-10 一键生成全部班组核算单并确认（水电班可看到 DN25 切割 42 元计入班组责任损耗与扣款，仓管发错的 DN20 切割计入项目承担）；「财务结算与索赔」查看供应商应付（扣不合格批次）、班组扣款、签证索赔。

## 五、主要 HTTP 接口（JWT：`Authorization: Bearer <token>`）

| 模块 | 接口 |
| --- | --- |
| 认证 | `POST /api/auth/login` |
| 元数据 | `GET /api/meta/options`（材料/供应商/班组/楼栋/工序下拉） |
| 库存/批次 | `GET /api/inventory`、`GET /api/batches`、`POST /api/batches/inbound`、`PUT /api/batches/{id}/qc` |
| 预算 | `GET/POST /api/budgets` |
| 领料 | `GET /api/requisitions`、`POST /api/requisitions`、`GET /api/requisitions/{id}/precheck`、`PUT /api/requisitions/{id}/issue`、`PUT /api/requisitions/{id}/reject` |
| 退库 | `GET/POST /api/returns`、`PUT /api/returns/{id}/confirm`、`PUT /api/returns/{id}/reject` |
| 规格退换 | `GET/POST /api/spec-exchanges`、`PUT /api/spec-exchanges/{id}/confirm`、`PUT /api/spec-exchanges/{id}/reject`、`GET /api/spec-exchanges/warnings?crewId=&materialId=` |
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

测试覆盖：登录鉴权与角色权限（401/403）、发料前预算/已领/库存核对、库存不足拦截、夜间紧急发料、规格领错退库回补、**规格退换料（照片必填/数量超领拦截/仓管确认回补库存/切割损耗 CREW 与 KEEPER 两种责任归属/班组再次申请同材料预警/班组长无权确认 403）**、盘点调账、月底核算金额口径（领用 4670、退库 756、净消耗 3914、偏差 −6686、班组扣款 42）、损耗复盘含规格领错分类、供应商质量索赔、采购计划反推。

## 七、工程结构

```
150-feature-1-a/
├── docker-compose.yml         # mysql + backend(spring) + frontend(nginx)
├── backend/                   # Spring Boot 3 / Java 17
│   ├── Dockerfile
│   └── src/main/java/com/site/material/
│       ├── domain/            # 16 个实体（批次/预算/领料/退库/规格退换/损耗/签证/盘点/核算…）
│       ├── repo/ service/ controller/
│       ├── security/          # JWT + 五种角色
│       └── config/DataInitializer.java   # 演示种子数据（全部场景）
└── frontend/                  # React 18 + Vite
    ├── Dockerfile nginx.conf
    └── src/pages/             # 15 个角色化页面（含规格领错退换）
```

## 八、已执行的验证结论

- 后端 `mvn package` 编译通过；`mvn test` 集成测试 **1 个用例、全部通过**（H2 内存库，2026-10-04 实测）。
- 后端 jar 以默认 H2 配置实际启动成功，退换料链路经 curl 逐环实测：
  种子待确认单（水电班 DN25，未使用 40/已切割 10、含现场照片）→ 仓管判定**班组申请错误**确认 → DN25 批次库存 1950→1990（回补 40 米）、生成 `SPEC_CUT` 损耗 42 元计 CREW；
  另发起一张 DN20 退换（未使用 8/已切割 2、带照片）→ 仓管判定**仓管发错** → 回补 28 元、切割 7 元计 COMPANY 不扣班组；
  同班组再申请 DN20 时 `POST /api/requisitions` 返回 `specWarnings`（2 次领错史），发料核对接口返回 `specWrongWarn`；
  无照片/数量超领返回 400、班组长确认退换单返回 403；财务生成水电班 2026-10 核算单，切割损耗按责任分别计入班组扣款 42 元与项目承担 42 元。
- 前端 `npm run build`（Vite）构建成功，产出 dist 静态资源（含新增「规格领错退换」页与领料预警条）。
- Docker Compose 配置已按多阶段构建编写；当前沙箱环境无 Docker 守护进程，未在本机实际执行 `docker compose up`，请在有 Docker 的机器上按第二节命令验证。
