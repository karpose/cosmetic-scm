# CLAUDE.md

化妆品品牌方供应链系统，一期“批次主线”。本文件给 Claude Code 每次会话读取；背景和决策过程见 `docs/HANDOFF.md`。

## 业务背景（决定设计的事实）

- 品牌方（化妆品注册人 / 备案人），成长期，SKU 50–300，目前用表格或单一软件管理。
- 生产模式：委托代工（OEM/ODM）+ 自有工厂混合；代工物料“品牌供料”和“工厂代采”比例相当，同一 BOM 里两种都有。
- 渠道：国内电商（天猫、京东、抖音、小红书）、线下经销 / 门店、私域小程序。
- 合规依据：《化妆品生产质量管理规范》（GMP，2022-07-01 施行）、《企业落实化妆品质量安全主体责任监督管理规定》。README 第三节有条款到代码的对照表，改动相关逻辑时同步更新它。

## 常用命令

```bash
# 后端（JDK 17+，Maven 3.9）
cd backend && mvn test              # 单元测试 + 主线集成测试（H2 内存库）
cd backend && mvn spring-boot:run   # dev 配置：H2 文件库 ./data，自动写示例数据
rm -rf backend/data                 # 重置本地数据

# 前端（Node 18+）
cd frontend && npm install && npm run dev     # http://localhost:5173，/api 代理到 8080
cd frontend && npm run build
cd frontend && npm run e2e                     # Playwright 冒烟测试，自带内存库后端（8090）和前端（5174）
docker compose -f docker-compose.yml -f docker-compose.https.yml up -d --build   # 带 HTTPS 部署

# 整体部署
cp .env.example .env && docker compose up -d --build
```

示例账号（仅 dev）：admin / Admin@123456；purchase1、prod1、qa1、qso1、sales1、viewer1 / Demo@12345；门户账号 oem1（禾川生物）、dealer1（华东经销商）/ Demo@12345。

## 架构

- 后端 `backend/src/main/java/com/cosmetic/scm/`，按业务模块分包：master、purchase、inventory（含库存作业 StockOps）、production、quality、sales、salesreturn、complaint、recall、oemportal、dealer、integration、planning、serial、analytics、trace、dashboard、audit、security、demo。跨模块只通过 Repository 读写，服务之间不互相注入，避免循环依赖。
- `domain/rules/` 是纯 Java 业务规则（FefoAllocator、ShelfLife、FormulaCompliance），不依赖框架，有单元测试。新的计算规则优先放这里。
- 实体之间只用 Long 外键字段，不用 JPA 关联映射；列表视图通过 `MasterLookup` 一次载入主数据 Map 再组装 record 视图。
- 表结构只由 Flyway 管理（`ddl-auto: none`）。改表新增 `V{n}__xxx.sql`，**绝不修改已有迁移脚本**。SQL 需同时兼容 MySQL 8 和 H2 MySQL 模式。
- 前端 `frontend/src/`：Vue 3 `<script setup>` + Element Plus，JavaScript（非 TS）。接口统一走 `src/api.js`（自动带 JWT，错误统一 `ElMessage` 提示，`{ silent: true }` 可静默）。主数据下拉用 `utils/refdata.js` 的 `useRefData()`。列表筛选 + 分页统一用 `utils/table.js` 的 `usePaged()` 和 `components/ListPager.vue`，加载失败用 `LoadError.vue`。
- 样式是 Liquid Glass：设计变量和 Element Plus 换皮都在 `src/styles.css`（`--lg-*` 变量、`.glass` 原语）。新页面直接用 `el-card` / `.glass`，不要写死背景色；颜色用变量，深浅色都要能看。

## 必须保持的规则

- 库存只能通过 `InventoryService.change/consume` 修改：行锁 + 不允许负数 + 每次写 `stock_txn` 流水。
- 所有业务写操作在一个 `@Transactional` 方法里完成，并在同一事务里调用 `AuditService.log`。审计日志只增不改，不提供修改 / 删除接口。
- 单号、批号由 `DocNoService.next(prefix, date)` 生成（必须在事务内）。
- FEFO 分配只取 `RELEASED` 批次；出库时再次确认批次状态（防止分配后被冻结）。退货隔离区（`WarehouseKind.RETURN`）的库存永远不参与分配，只能走退货处置。
- 成品上市放行接口只授予 `QSO` 角色（管理员也不行）；放行、拒收、冻结、解冻都通过 `SignatureService.verify` 要求再次输入密码。
- 召回发起 / 关闭只授予 `QSO`；发起时冻结批次并按出库记录逐客户生成通知，所有客户通知后才能关闭。不良反应投诉未登记上报不能关闭。报废、退货处置须 QA / QSO 签批。
- 权限边界在 `SecurityConfig` 的 URL 层：`/api/portal/oem/**` 只给 OEM_PORTAL，`/api/portal/dealer/**` 只给 DEALER，`/api/integration/**` 只认 X-Api-Key，`/api/public/**` 和 `/api/health` 无需登录（只能放不含内部数据的接口，前端调用时加 `{ public: true }` 不带令牌），其余 `/api/**` 只给内部角色。门户服务里的每个查询都必须用 `CurrentUser.portalPartnerId()` 过滤，别人的数据一律当“不存在”。新增内部接口不需要额外处理，新增门户接口要放在对应前缀下。
- 登录失败计数要落库，`LoginService.login` 对 BizException 不回滚；密码规则统一走 `PasswordPolicy`。
- 委托生产完工前必须确认受托厂已出厂放行；实际产量超出计划 95%–102% 必须填物料平衡说明；上市放行必须登记留样，保存期 = 有效期 + 6 个月。
- 供应商 / 受托厂不在合格名录或资质过期，不能下单；禁用原料不能采购、不能进 BOM；限用原料按净含量校验浓度上限。
- 示例数据只通过业务服务写入（`DemoDataLoader`），日期相对“今天”生成，名称带“（示例）”。

## 代码约定

- 面向用户的错误用 `BizException`，消息写中文、说清怎么改；不要把异常堆栈返回前端。
- DTO 用 Java record，放在对应 Service 或 `XxxDtos` 里；请求参数用 jakarta validation 注解。
- 金额 / 数量一律 `BigDecimal`，比较用 `compareTo`；数据库数量列 DECIMAL(18,4)，计算需求量时 `setScale(4, RoundingMode.UP)`。
- 时间取 `TimeProvider.today()/now()`（Asia/Shanghai），不要直接 `LocalDate.now()`。
- 前端文案用中文，按钮写清动作（“确认收货”“签批”），不要用“提交”“确定”这种泛词。

## 当前状态（2026-11-03）

- 后端 `mvn clean test` 36 项全部通过（JDK 17 和 27 都做过全量重编译验证）；前端 `npm run build` 通过；Playwright 冒烟测试 10 项全部通过；GitHub Actions 在 PR 上跑这三项。
  - `Item.netG` 需要显式 `@Column(name = "net_g")`：以单个大写字母结尾的字段名默认会映射错。测试配置开启了 `ddl-auto: validate`。
  - Lombok 已升到 1.18.48（Boot 3.3 自带的 1.18.34 在 JDK 27 上编译崩溃）。判断“某 JDK 能编译”时务必 `mvn clean`。
- 已完成：一期主线；库存作业、销售退货、投诉与召回、主数据 CSV 导入；代工厂门户、经销商门户、电商对接骨架；登录锁定、密码策略；补货建议、一物一码、经营看板（V4 迁移）；Liquid Glass 界面；HTTPS 叠加配置、自动备份 / 恢复脚本、健康检查；`docs/csv/` 验证文档包。
- **没有实测过的**：Docker 部署整套（本机 Docker 没运行）——HTTPS 配置、备份容器、后端健康检查都只做了语法 / 格式检查。第一次在服务器上部署时要逐项按 `docs/csv/03-安装确认-IQ.md` 核对。
- 看板图表配色用 dataviz 参考调色板，已用校验脚本在浅色、深色下验过；图表是纯 HTML/CSS，没有引入图表库。
- 示例数据含：老批次精华液（效期门槛、呆滞）、退货隔离区 + 待处置退货、待上报的不良反应投诉、受托厂已排产的委托单、经销商待审核订单、一张缺货失败的电商订单、第一批精华液的 20 个防伪码。

## 下一步（按优先级）

1. 在服务器上第一次 Docker 部署，按 IQ 逐项核对（HTTPS、备份、健康检查都还没实测）；配置 `./backups` 异地同步。
2. 用真实 SKU、供应商、代工厂、客户替换示例主数据（CSV 导入），给受托厂和经销商开门户账号；质量部按 `docs/csv/` 执行 OQ / PQ 并签字。
3. 选定电商 ERP（聚水潭、旺店通、吉客云等）后，在 `integration` 包里写报文适配和回调 / 定时拉取。
4. 门户附件上传（检验报告扫描件等），需要先定文件存储方案。
5. 接入成本和售价后，看板改按金额；需要时把补货算法升级为带季节性的预测。
