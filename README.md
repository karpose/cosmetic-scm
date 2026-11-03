# 化妆品批次主线系统（一期）

化妆品品牌方供应链系统的一期工程，覆盖规划方案里的主线：

**主数据 → 采购收货 → 物料检验放行 → 生产 / 委托生产 → 成品上市放行 → FEFO 销售出库 → 批次正反向追溯**，全程审计留痕。

| 层 | 技术 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.3、Spring Data JPA、Spring Security（JWT）、Flyway |
| 数据库 | MySQL 8（生产）；本地开发用 H2 文件库，免安装 |
| 前端 | Vue 3、Vite 5、Element Plus、Pinia、Vue Router |
| 部署 | Docker Compose（MySQL + 后端 + nginx） |

---

## 一、本地运行（开发）

需要：JDK 17 或以上、Maven 3.9、Node.js 18 或以上。

```bash
# 1. 启动后端（默认 dev 配置：H2 文件库 + 自动写入示例数据）
cd backend
mvn spring-boot:run
# 看到 “已写入示例数据” 的日志即启动完成，接口在 http://localhost:8080/api

# 2. 另开一个终端启动前端
cd frontend
npm install
npm run dev
# 浏览器打开 http://localhost:5173
```

国内网络如果 Maven 或 npm 下载慢，可以分别配置阿里云 Maven 镜像和 `npm config set registry https://registry.npmmirror.com`。

### 账号

| 账号 | 密码 | 角色 | 用来体验 |
| --- | --- | --- | --- |
| admin | Admin@123456 | 系统管理员、主数据管理 | 主数据、账号与权限 |
| purchase1 | Demo@12345 | 采购、仓库 | 下采购单、收货 |
| prod1 | Demo@12345 | 生产计划、仓库 | 生产单、发料、完工入库 |
| qa1 | Demo@12345 | 质量检验 | 物料检验放行、冻结批次 |
| qso1 | Demo@12345 | 质量安全负责人、质量检验 | **成品上市放行**、解冻 |
| sales1 | Demo@12345 | 销售 | 销售出库 |
| viewer1 | Demo@12345 | 只读 | 只能查看 |

示例账号只在 dev 配置下创建。admin 的初始密码由 `SCM_ADMIN_PASSWORD` 决定，**首次登录后请立即修改**。

### 示例数据里能直接走的流程

启动后总览页会出现这些待办，可以按顺序体验完整主线：

1. **物料待检**：用 `qa1` 在“质量放行”给新到的烟酰胺签批放行（需要再次输入密码）。
2. **待发料**：用 `prod1` 在“生产与委托”打开自产面霜工单，点“发料”。预览里会看到苯氧乙醇先用了临近效期的旧批次（先到期先出）。然后点“完工入库”。
3. **待上市放行**：用 `qso1` 审核签批两个成品批次。`qa1` 没有这个权限，按钮是灰的。
4. **销售出库**：用 `sales1` 给京东自营入仓发烟酰胺精华液。该客户要求剩余效期 ≥ 67%，预览里会提示一批 14 个月前生产的老批次（剩余约 61%）被排除；换成天猫旗舰店（≥ 50%），这批老货会按先到期先出被优先分配。
5. **追溯**：在“批次追溯”输入任一批号，向上能查到原料批次和受托厂的代采原料批号，向下能查到发往的客户。用 `qa1` 可以一键冻结受影响批次。

想重新开始：停掉后端，删除 `backend/data/` 目录后重新启动。

---

## 二、部署到服务器（Docker）

```bash
cp .env.example .env      # 修改里面所有密码和密钥
docker compose up -d --build
# 浏览器访问 http://服务器地址
```

- 数据库表结构由 Flyway 在后端启动时自动创建和升级（`backend/src/main/resources/db/migration`）。以后改表请新增 `V2__xxx.sql`，**不要修改已执行过的脚本**。
- 生产环境默认不写示例数据。测试环境想要示例数据，把 `.env` 里 `SCM_DEMO_DATA` 设为 `true`。示例账号同样会被创建，正式上线前务必关掉。
- 正式上线建议：nginx 前加 HTTPS；MySQL 每日备份并异地保存（记录保存期限见下文）；`SCM_JWT_SECRET` 用随机长字符串并妥善保管。

---

## 三、合规要求在代码里的落地点

依据《化妆品生产质量管理规范》（下称 GMP）和《企业落实化妆品质量安全主体责任监督管理规定》。

| 要求 | 依据 | 代码位置 / 做法 |
| --- | --- | --- |
| 原料、内包材、成品按批号管理，全程可追溯 | GMP 第 14 条 | `batch`、`batch_genealogy`、`mfg_issue`、`so_allocation` 四张表串起来；`TraceService` 正反向追溯 |
| 禁用原料不得使用，限用原料不得超限 | GMP 第 29 条 | `FormulaCompliance`：BOM 保存时校验；采购单拒绝禁用原料 |
| 合格供应商名录，资质过期不能下单 | GMP 第 28、48、51 条 | `Partner.usable()`；采购单、生产单下单前检查；到期前 60 天在总览提醒 |
| 物料放行后才能用于生产 | GMP 第 32 条 | 收货批次为“待检”；FEFO 分配只取“已放行”批次 |
| 委托方在受托厂出厂放行基础上上市放行 | GMP 第 45、55 条 | 成品完工为“待上市放行”；委托生产必须勾选受托厂已放行；上市放行接口只授予 `QSO` 角色 |
| 每批留样，保存至有效期满后 6 个月 | GMP 第 18 条 | 上市放行必须登记留样数量和地点，系统自动计算保存期限 |
| 物料平衡超限需查明原因 | GMP 第 41 条 | 实际产量超出计划的 95%–102% 时必须填写说明 |
| 电子记录：用户唯一、权限控制、操作留痕 | GMP 附 1 | 账号 + 角色；关键签批要求再次输入密码（`SignatureService`）；`audit_log` 只增不改 |
| 销售记录含批号、客户名称地址联系方式 | GMP 第 58 条 | 出库单记录批次；客户主数据有联系人和地址 |
| 召回时找到受影响客户 | GMP 第 63 条 | 追溯页列出发往客户，并可一键冻结受影响批次 |
| 先到期先出、客户效期接收标准 | 方案第 6 节 | `FefoAllocator`：排除过期批次和剩余效期低于客户标准的批次 |

---

## 四、工程结构

```
backend/
  src/main/java/com/cosmetic/scm/
    domain/rules/      纯业务规则（FEFO、效期、配方浓度），不依赖框架，有单元测试
    domain/            枚举（批次状态、物料类型、角色等）
    master/            主数据：物料、往来单位、仓库、BOM
    purchase/          采购单、收货
    inventory/         批次、库存结存、库存流水、FEFO 分配入口
    production/        自产工单与委托生产单、发料、完工
    quality/           物料检验放行、成品上市放行、冻结 / 解冻
    sales/             销售出库
    trace/             批次追溯
    dashboard/         总览
    audit/             审计日志
    security/          登录、JWT、账号与角色、电子签名
    demo/              示例数据（通过真实业务服务写入）
  src/main/resources/db/migration/   Flyway 建表脚本
  src/test/                          单元测试 + 主线集成测试
frontend/
  src/views/           各业务页面
  src/components/      批号链接、状态标签、效期条
  src/api.js           接口调用与统一错误提示
```

### 角色与权限

| 操作 | 允许的角色 |
| --- | --- |
| 维护主数据 | ADMIN、MASTER_DATA |
| 下采购单 | ADMIN、PURCHASE |
| 收货 | ADMIN、PURCHASE、WAREHOUSE |
| 生产单 | ADMIN、PRODUCTION（发料、完工还允许 WAREHOUSE） |
| 物料检验放行、冻结 | QA、QSO |
| **成品上市放行、解冻** | **仅 QSO（质量安全负责人）** |
| 销售出库 | ADMIN、SALES、WAREHOUSE |
| 账号管理 | ADMIN |
| 查看 | 所有登录用户 |

---

## 五、测试

```bash
cd backend
mvn test
```

- `FefoAllocatorTest`、`ShelfLifeAndFormulaTest`：先到期先出、客户效期门槛、过期排除、限用原料浓度等规则。
- `MainLineIntegrationTest`：启动完整应用（内存数据库），示例数据走完一遍主线后，校验库存数、追溯结果、客户效期门槛（京东排除老批次、天猫优先用老批次）；并验证未登录被拒、质量检验员不能上市放行、签批密码错误被拒。

---

## 六、一期没有做、后续计划

- 电商平台（天猫、京东、抖音、小红书）订单和库存接口：二期按所选电商 ERP 对接。
- 代工厂协同门户、经销商订货门户：二期。
- 财务凭证、成本核算：通过接口对接现有财务系统。
- 一物一码、S&OP 预测：三期。
- 计算机化系统验证（CSV）：上线前需按 GMP 附 1 做系统验证并留存记录。
- 登录失败锁定、密码定期更换策略：按公司信息安全制度补充。
