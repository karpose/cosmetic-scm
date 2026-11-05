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

- 后端 `backend/src/main/java/com/cosmetic/scm/`，按业务模块分包：master、purchase、inventory（含库存作业 StockOps、扫码 ScanService）、production、quality、sales、salesreturn、complaint、recall、oemportal、dealer、integration、planning、serial、analytics、attachment、plm、qms、mes、report、notify、trace、dashboard、audit、security、demo。跨模块只通过 Repository 读写，服务之间不互相注入，避免循环依赖。
- `domain/rules/` 是纯 Java 业务规则（FefoAllocator、ShelfLife、FormulaCompliance、FormulaDiff、Replenishment、DemandForecast），不依赖框架，有单元测试。新的计算规则优先放这里。
- 实体之间只用 Long 外键字段，不用 JPA 关联映射；列表视图通过 `MasterLookup` 一次载入主数据 Map 再组装 record 视图。
- 表结构只由 Flyway 管理（`ddl-auto: none`）。改表新增 `V{n}__xxx.sql`，**绝不修改已有迁移脚本**。SQL 需同时兼容 MySQL 8 和 H2 MySQL 模式。
- 前端 `frontend/src/`：Vue 3 `<script setup>` + Element Plus，JavaScript（非 TS）。接口统一走 `src/api.js`（自动带 JWT，错误统一 `ElMessage` 提示，`{ silent: true }` 可静默）。主数据下拉用 `utils/refdata.js` 的 `useRefData()`。列表筛选 + 分页统一用 `utils/table.js` 的 `usePaged()` 和 `components/ListPager.vue`，加载失败用 `LoadError.vue`。
- 样式是 Liquid Glass：设计变量和 Element Plus 换皮都在 `src/styles.css`（`--lg-*` 变量、`.glass` 原语）。新页面直接用 `el-card` / `.glass`，不要写死背景色；颜色用变量，深浅色都要能看。
- 无障碍：冒烟测试用 axe 查 WCAG A/AA，新页面要能过。不在带 label 的 `el-form-item` 里的 `el-select`、`el-input-number`、`el-progress` 要写 `aria-label`（占位文字不算名称）；表格可能滚动又没有可聚焦内容时加 `scrollbar-tabindex="0"`。文字色用 `--el-text-color-*` 和 `--el-color-*`（浅色已调到 4.5:1），不要写死 Element 默认色。axe 判断不了半透明和渐变背景，改颜色时要另外算。侧栏是 `<nav>` + 真实链接，不是 `el-menu`。

## 必须保持的规则

- 库存只能通过 `InventoryService.change/consume` 修改：行锁 + 不允许负数 + 每次写 `stock_txn` 流水。
- 所有业务写操作在一个 `@Transactional` 方法里完成，并在同一事务里调用 `AuditService.log`。审计日志只增不改，不提供修改 / 删除接口。
- 单号、批号由 `DocNoService.next(prefix, date)` 生成（必须在事务内）。
- FEFO 分配只取 `RELEASED` 批次；出库时再次确认批次状态（防止分配后被冻结）。退货隔离区（`WarehouseKind.RETURN`）的库存永远不参与分配，只能走退货处置。
- 成品上市放行接口只授予 `QSO` 角色（管理员也不行）；放行、拒收、冻结、解冻都通过 `SignatureService.verify` 要求再次输入密码。
- 召回发起 / 关闭只授予 `QSO`；发起时冻结批次并按出库记录逐客户生成通知，所有客户通知后才能关闭。不良反应投诉未登记上报不能关闭。报废、退货处置须 QA / QSO 签批。
- 单据状态流转必须先锁行再判断状态（各 Repository 的 `lockById`，只在写事务里用，读事务里 FOR UPDATE 在 MySQL 上会报错）。检查和修改之间不加锁，连点按钮就会重复入库，实测过。出库和发料用 `InventoryService.allocateForUpdate`，预览用 `allocate`。库存行加锁走 `lockStock`：实体已载入时直接用锁查询，Hibernate 会因版本不同报冲突。
- “算出来才知道能不能做”的操作（退货可退量 = 已发 - 已退、同批次只能有一个进行中的召回）没有单据可锁，要锁它们依赖的行（客户、批次），而且必须是事务里第一条语句：MySQL 可重复读下，锁之前有过普通查询，之后读不到别人刚提交的数据。出库失败回滚后另开事务写“失败”时也要重新锁并判断状态，不能盖掉同时发生的取消。
- MySQL 的 `DATETIME` 会把小数秒四舍五入（H2 不同）。要和令牌 iat 比较的时间（`passwordChangedAt`）必须先截断到整秒，曾因此让一半用户改密后被踢出。
- 令牌只证明身份，角色每次从数据库读（`DbJwtAuthenticationConverter`），不要往令牌里加权限判断。改密后旧令牌失效，改密接口返回新令牌。
- 列表和统计不能靠“最近 N 条”做判断（曾因此漏掉最老的临期批次）。不要 `findAll` 全表：用分组或专门的查询。看板、补货建议的短时缓存在 Controller 层（`ShortCache`），测试和开发环境 ttl 为 0。
- 权限边界在 `SecurityConfig` 的 URL 层：`/api/portal/oem/**` 只给 OEM_PORTAL，`/api/portal/dealer/**` 只给 DEALER，`/api/integration/**` 只认 X-Api-Key，`/api/public/**` 和 `/api/health` 无需登录（只能放不含内部数据的接口，前端调用时加 `{ public: true }` 不带令牌），其余 `/api/**` 只给内部角色。门户服务里的每个查询都必须用 `CurrentUser.portalPartnerId()` 过滤，别人的数据一律当“不存在”。新增内部接口不需要额外处理，新增门户接口要放在对应前缀下。
- 附件只增不删，存储用 `CREATE_NEW` 不覆盖；上传校验白名单扩展名和文件头，记录 SHA-256，下载时重新核对。文件在 `scm.storage.dir`（Docker 里是 `files-data` 卷），数据库只存元数据。受托厂上传走 `/api/portal/oem/orders/{moId}/attachments`，只能传本厂未完工的单子。
- `spring.servlet.multipart` 的上限必须高于 nginx 的 `client_max_body_size`（现在 30 MB 对 25 MB）。业务上限（20 MB）在 `AttachmentService` 判断。反过来配置会让后端没读完请求体就回错误，和 nginx 互相等待到超时。
- 金额是标准成本估值：`MasterService.unitCosts()` 优先用标准成本，成品退回 BOM 物料成本；没成本的物料不计入，并显示数量提示。不要当账面成本用。
- 配方：`bom` / `bom_line` 永远是当前生效版本的内容，其它模块照常读。生效配方不能直接改（`MasterService.saveBom` 只接受首版），要走 `FormulaService`：草稿 → 提交 → QSO 签批，发起人不能批自己的；原料变化必须有该版本 FILED 的 `product_filing`。生产单 `formula_version_id`：未发料跟随生效版本，发料后锁定，用量按版本读（`MasterService.versionLines`）。
- 备案编号：首次可在主数据或 CSV 里填（同时生成一条 FILED 记录，便于上线初始化）；有已完成的记录后只能走 `FilingService`，`MasterService.updateItem` 拒绝直接修改。
- 放行的前置检查在 `QualityService`：批次有未关闭的重大及以上偏差、注册证过期都不能放行。放行时写 `retain_sample`，这是留样台账的唯一入口。
- 批记录（`MesService`）只对自产且发料时有工艺路线的单子生效，在发料事务里固化。`ProductionService.complete` 先查 `mes.missing()`，并把这张单的偏差挂到产出批次。工序记录提交后不提供修改接口。过程检验超限不阻止生产，但必须自动生成偏差。
- 签批、复核、验证类操作的执行人不能是操作人本身（CAPA 完成人、工序操作人、配方发起人），规则写在服务层，不靠界面隐藏。
- 导出在服务端生成（`ReportService` + `common/XlsxWriter`），每次写审计。单元格文本只写内联字符串，不写公式。新增报表在 `registry()` 里加一项。预测规则在 `domain/rules/DemandForecast`，历史按首次出库到上个完整月。
- 提醒内容直接取 `DashboardService.summary()` 的待办，不另算；新的到期规则加到总览待办就会进提醒，新的待办类型要在 `TodoRouting` 里指定岗位，否则不进个人邮件。错误信息写库前要去掉 webhook 地址（含密钥）。
- 生产单取消（`ProductionService.cancel`）：未发料直接取消；已发料未开工退料并删除 `mfg_issue`（否则追溯会显示批次流向这张单，流水和审计保留）；开工后（`mes.started`）或受托厂已回传生产中、已申报的不能取消。新增读生产单状态的代码要想到 CANCELLED。采购单：没收货 CANCELLED，部分收货 CLOSED。
- 现有库存只能走期初导入（`OpeningStockService`，来源 `SourceType.OPEN`，流水 `OPENING`），不要造采购单或生产单。已放行的批次写放行记录，成品写留样，所以只给 QSO 并要签批。行上不加 `@Valid`，缺字段要按 Excel 行号报。
- 工序勾了“须记录设备”时，`MesService.recordStep` 用 `EquipmentService.unusableReason` 校验；校准不合格的处理在 `EquipmentService.calibrate`，设备停用并生成偏差。
- 导出（`ReportService`）必须用 `listAll` 类查询，不能用页面的 Top300 查询；超过 `MAX_ROWS` 报错，不截断。页面列表到上限时用 `ListCapHint` 提示。
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

## 当前状态（2026-11-04）

- 后端 `mvn clean test` 110 项全部通过；前端 `npm run build` 通过；Playwright 冒烟测试 24 项全部通过（含 axe 无障碍检查）；GitHub Actions 在 PR 上跑这三项。
  - `Item.netG` 需要显式 `@Column(name = "net_g")`：以单个大写字母结尾的字段名默认会映射错。测试配置开启了 `ddl-auto: validate`。
  - Lombok 钉在 1.18.48（旧版在 JDK 27 上编译崩溃）。Spring Boot 3.5.16。判断“某 JDK 能编译”时务必 `mvn clean`。
- 已完成：一期主线；库存作业、销售退货、投诉与召回、主数据 CSV 导入；代工厂门户、经销商门户、电商对接骨架；登录锁定、密码策略；补货建议、一物一码、经营看板（V4 迁移）；标准成本与看板金额、附件管理（V5 迁移）；批次标签打印和扫码核对；配方版本与备案、偏差与 CAPA、供应商审核、留样台账、自产批生产记录、Excel 导出、每日提醒（V6 迁移）；单据取消和关闭、全量导出、提醒按岗位分发、账号邮箱（V7 迁移）、带季节性的需求预测；期初库存导入、设备台账与校准（V8 迁移）；配方批量导入、上线检查页；Liquid Glass 界面；HTTPS 叠加配置、自动备份 / 恢复脚本、健康检查；`docs/csv/` 验证文档包。
- Docker 部署已在本机 Colima（profile `cosmetic-scm`）上完整演练过：MySQL 8 上迁移和示例数据都正常，HTTPS、首次改密、备份恢复、数据库宕机时的健康检查都实测通过，记录见 `docs/csv/07-部署预验证记录-2026-11-03.md`（含历次升级、备份恢复、并发、容量测试和空库上线演练）。所有自动化测试都带示例数据，改动上线流程后要在空库上再走一遍。并发、死锁和 CSP 这类问题 H2 和开发服务器测不全，改到这些要到 MySQL 上跑。CSP、Permissions-Policy 和上传限制只有经过 nginx 才生效，开发服务器测不出来，改这些要走一遍 Docker。这台 Colima 没有共享宿主机目录，绑定挂载会是空目录；里面还有一套用 8088 端口的 `cosmetic-scm` 旧部署，不要动它。
- 看板图表配色用 dataviz 参考调色板，已用校验脚本在浅色、深色下验过；图表是纯 HTML/CSS，没有引入图表库。
- 示例数据含：精华液 V2 配方待签批且备案在途、面霜工艺路线、明瓶包装审核快到期、电子秤校准快到期、一条微小偏差和 CAPA；老批次精华液（效期门槛、呆滞）、退货隔离区 + 待处置退货、待上报的不良反应投诉、受托厂已排产的委托单、经销商待审核订单、一张缺货失败的电商订单、第一批精华液的 20 个防伪码。

## 下一步（按优先级）

1. 在正式服务器上部署，按 IQ 逐项核对（开发机上已演练通过），按 SOP-09 切换；配置 `./backups` 异地同步；用现场的标签打印机和扫码枪执行 OQ-32（还没有用真实硬件测过）。
2. 用真实 SKU、供应商、代工厂、客户替换示例主数据（CSV 导入），给受托厂和经销商开门户账号；质量部按 `docs/csv/` 执行 OQ / PQ 并签字。
3. 选定电商 ERP（聚水潭、旺店通、吉客云等）后，在 `integration` 包里写报文适配和回调 / 定时拉取。
4. 用现场工艺替换示例工艺路线，用真实邮箱和企业微信群验证提醒。
5. 需要时：预测加促销等人工调整；实际成本核算对接财务系统；称量数据采集；即时提醒。
