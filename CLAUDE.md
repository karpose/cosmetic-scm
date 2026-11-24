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

# 前端（Node 20.19+，Vite 8 的要求）
cd frontend && npm install && npm run dev     # http://localhost:5173，/api 代理到 8080
cd frontend && npm run build
cd frontend && npm run e2e                     # Playwright 冒烟测试，自带内存库后端（8090）和前端（5174）
cd frontend && npm run e2e:empty               # 空库走查：无示例数据、开启强制改密，只用页面走上线和业务（8091、5175）
docker compose -f docker-compose.yml -f docker-compose.https.yml up -d --build   # 带 HTTPS 部署

# 整体部署
cp .env.example .env && docker compose up -d --build
```

示例账号（仅 dev）：admin / Admin@123456；purchase1、prod1、qa1、qso1、sales1、viewer1 / Demo@12345；finance1 / Demo@12345；门户账号 oem1（禾川生物）、dealer1（华东经销商）、sup1（华澄化工）/ Demo@12345。

## 架构

- 后端 `backend/src/main/java/com/cosmetic/scm/`，按业务模块分包：master、purchase、inventory（含库存作业 StockOps、扫码 ScanService）、production、quality、sales、salesreturn、complaint、recall、oemportal、supplierportal、sourcing、settlement、supplierreturn、dealer、integration、planning、serial、analytics、attachment、plm、qms、mes、report、notify、trace、dashboard、audit、security、demo。跨模块只通过 Repository 读写，服务之间不互相注入，避免循环依赖。
- `domain/rules/` 是纯 Java 业务规则（FefoAllocator、ShelfLife、FormulaCompliance、FormulaDiff、Replenishment、DemandForecast、SpecLimit、CasNumber），不依赖框架，有单元测试。新的计算规则优先放这里。
- 实体之间只用 Long 外键字段，不用 JPA 关联映射；列表视图通过 `MasterLookup` 一次载入主数据 Map 再组装 record 视图。
- 表结构只由 Flyway 管理（`ddl-auto: none`）。改表新增 `V{n}__xxx.sql`，**绝不修改已有迁移脚本**。SQL 需同时兼容 MySQL 8 和 H2 MySQL 模式。
- 前端 `frontend/src/`：Vue 3 `<script setup>` + Element Plus，JavaScript（非 TS）。接口统一走 `src/api.js`（自动带 JWT，错误统一 `ElMessage` 提示，`{ silent: true }` 可静默）。主数据下拉用 `utils/refdata.js` 的 `useRefData()`；弹窗要用它设默认值时先 `await rd.ready()`，否则网络慢时默认值一直是空的。查询条件会变的请求（切换仓库、单据、批号）用 `utils/latest.js` 的 `latestOnly()`，先发的请求可能晚回来：盘点表曾因此显示 A 仓、提交成 B 仓。弹窗打开后才异步带出的数据（如可退批次）到手之前，提交按钮要禁用，否则点了只提示“请至少填一行”，响应回来又自动填上。带 `:loading` 的提交按钮加载中是禁用的，浏览器这时不响应回车，要在输入框上处理 `@keydown.enter.prevent`。上传、下载和导入用 `LONG_TIMEOUT`，默认 30 秒传不完大附件。`router.onError` 处理升级后旧页面懒加载失败（自动刷新一次）。列表筛选 + 分页统一用 `utils/table.js` 的 `usePaged()` 和 `components/ListPager.vue`，加载失败用 `LoadError.vue`。
- 样式是 Liquid Glass：设计变量和 Element Plus 换皮都在 `src/styles.css`（`--lg-*` 变量、`.glass` 原语）。新页面直接用 `el-card` / `.glass`，不要写死背景色；颜色用变量，深浅色都要能看。
- 无障碍：冒烟测试用 axe 查 WCAG A/AA，新页面要能过。不在带 label 的 `el-form-item` 里的 `el-select`、`el-input-number`、`el-progress` 要写 `aria-label`（占位文字不算名称）；表格可能滚动又没有可聚焦内容时加 `scrollbar-tabindex="0"`。文字色用 `--el-text-color-*` 和 `--el-color-*`（浅色已调到 4.5:1），不要写死 Element 默认色。axe 判断不了半透明和渐变背景，改颜色时要另外算。侧栏是 `<nav>` + 真实链接，不是 `el-menu`。
- 手机宽度（≤768px）：侧栏是抽屉，默认藏起来。弹窗宽度可以照常写，全局限制了不超出屏幕。放不下的表格列要想清楚：要填数据的列（订购数量、实盘）不能靠横向滚动才看到，把次要列并进第一列（`narrow` 判断，见 `PortalDealer.vue`、`InventoryOps.vue`）。同一个 `el-form-item` 里放多个控件时，只有第一个关联 label，其余要写 `aria-label`。不要用 `el-upload` 包按钮（嵌套交互控件），见 `AttachmentList.vue`。

## 必须保持的规则

- 库存只能通过 `InventoryService.change/consume` 修改：行锁 + 不允许负数 + 每次写 `stock_txn` 流水。
- 所有业务写操作在一个 `@Transactional` 方法里完成，并在同一事务里调用 `AuditService.log`。审计日志只增不改，不提供修改 / 删除接口。修改主数据和账号要记录修改前后的值（`common/FieldDiff`），只记“修改了物料”事后查不出改了什么。新字段记得加进 `itemFields` / `partnerFields` / `userFields`（物料 CAS 号已加）。
- 盘点只能调整该仓已有记录的批次（`InventoryService.adjustTo`），没有记录的不按账面 0 算盘盈，否则错仓提交会凭空多出库存。
- 单号、批号由 `DocNoService.next(prefix, date)` 生成（必须在事务内）。
- FEFO 分配只取 `RELEASED` 批次；出库时再次确认批次状态（防止分配后被冻结）。退货隔离区（`WarehouseKind.RETURN`）的库存永远不参与分配，只能走退货处置。
- 成品上市放行接口只授予 `QSO` 角色（管理员也不行）；放行、拒收、冻结、解冻都通过 `SignatureService.verify` 要求再次输入密码。
- 召回发起 / 关闭只授予 `QSO`；发起时冻结批次并按出库记录逐客户生成通知，所有客户通知后才能关闭。召回进行中不能解冻。回收进度按“发出 - 普通退货”算在外量（普通退货转可售后可能再发给别人，只看发出量永远到不了 100%）。期初批次没有发货记录也能发起召回，因为上线前可能已发出，名单要线下核对。不良反应投诉未登记上报不能关闭。报废、退货处置须 QA / QSO 签批。
- 单据状态流转必须先锁行再判断状态（各 Repository 的 `lockById`，只在写事务里用，读事务里 FOR UPDATE 在 MySQL 上会报错）。检查和修改之间不加锁，连点按钮就会重复入库，实测过。出库和发料用 `InventoryService.allocateForUpdate`，预览用 `allocate`。库存行加锁走 `lockStock`：实体已载入时直接用锁查询，Hibernate 会因版本不同报冲突。**加锁读取之前不能先把同一行读进持久化上下文**（比如先 `findById` 拿外键再 `lockById`）：锁查询会返回缓存里的旧对象，状态判断失效，MySQL 上 6 个并发签样全成功过；要外键就只查那一列（如 `ArtworkProofRepository.findArtworkIdById`）。
- “算出来才知道能不能做”的操作（退货可退量 = 已发 - 已退、同批次只能有一个进行中的召回）没有单据可锁，要锁它们依赖的行（客户、批次），而且必须是事务里第一条语句：MySQL 可重复读下，锁之前有过普通查询，之后读不到别人刚提交的数据。出库失败回滚后另开事务写“失败”时也要重新锁并判断状态，不能盖掉同时发生的取消。
- MySQL 的 `DATETIME` 会把小数秒四舍五入（H2 不同）。要和令牌 iat 比较的时间（`passwordChangedAt`）必须先截断到整秒，曾因此让一半用户改密后被踢出。
- 令牌只证明身份，角色每次从数据库读（`DbJwtAuthenticationConverter`），不要往令牌里加权限判断。改密后旧令牌失效，改密接口返回新令牌。
- 列表和统计不能靠“最近 N 条”做判断（曾因此漏掉最老的临期批次、半年前没上报的不良反应，呆滞金额也只加了列表里的 10 个）。列表可以截断，合计要按全部算。可用库存不含已过期批次。补货日均和预测历史扣普通退货，召回回收的不扣（货确实卖掉了）。期初导入写的放行记录不算放行周期。呆滞只看发料和出库，调拨不算。看板和补货的手算回归在 `NumbersGroundTruthTest`（关掉示例数据）。不要 `findAll` 全表：用分组或专门的查询。看板、补货建议的短时缓存在 Controller 层（`ShortCache`），测试和开发环境 ttl 为 0。
- 权限边界在 `SecurityConfig` 的 URL 层：`/api/portal/oem/**` 只给 OEM_PORTAL，`/api/portal/dealer/**` 只给 DEALER，`/api/portal/supplier/**` 只给 SUPPLIER_PORTAL，`/api/integration/**` 只认 X-Api-Key，`/api/public/**` 和 `/api/health` 无需登录（只能放不含内部数据的接口，前端调用时加 `{ public: true }` 不带令牌），其余 `/api/**` 只给内部角色。门户服务里的每个查询都必须用 `CurrentUser.portalPartnerId()` 过滤，别人的数据一律当“不存在”。新增内部接口不需要额外处理，新增门户接口要放在对应前缀下。
- 供应商门户（`supplierportal`）：准时率按 `first_promised_date` 算，这个字段写入后不能再改，否则改一次交期就能把迟到变成准时。没承诺过的按采购期望日，都没有的不计。送货通知只是预告，库存和批次只由 `PurchaseService.receive` 生成。供应商只能看本单位账号上传的文件（内部传到单据和单位上的审核报告不开放）；新增这类接口时，锁必须是事务第一句，所以先 `lockById` 再判断归属。供应商提醒在 `NotifyService.supplierDigests`，内容只能有本单位的事，只走定时任务，`sendNow` 不发给供应商（管理员点测试不该打扰外部单位），也不受内部有没有待办影响。评分 `SupplierScore` 只作展示，不进下单判断，也不给供应商看；权重是默认值，要质量部确认。
- 询价比价（`sourcing`）：开标前接口不能返回价格，审计也不能记价格（不是只在页面上藏）。定标人不能是询价人，要签批。对账结算（`settlement`）：只结算 `RELEASED` 的来料批次；可结算批次是算出来的，所以生成对账单先锁供应商行，且必须是事务第一句，否则同一批次会结算两次。发票金额要和合计完全一致，审批人不能是制单人。单价和金额只给 ADMIN、PURCHASE、FINANCE（`PriceAccess`）：`PoLineView` 按角色隐藏；审计里带金额的用 `AuditService.logMoney`，读取时遮盖、搜索也不匹配，遮盖返回副本，不能改实体；总览所有内部岗位都能看，待办文字不能带金额。`SourcingSettlementTest.pricesAndAmountsDoNotLeakToOtherInternalRoles` 会扫所有 GET 接口，曾查出 59 处泄露，新接口自动被扫到。生成对账单后批次不再是放行状态的，不能审批和付款。这些都是默认规则，清单在 `docs/SRM-默认规则.md`，改规则时同步更新。
- 发票登记的四个入口统一使用 `READ_COMMITTED`。先读取对账单不可变的供应商 ID 并核对门户归属，再按供应商、对账单的顺序加锁，等锁后查重才能看到另一笔刚提交的登记。查重以未退回的发票明细为准，旧单缺少明细时才查汇总栏。
- 退供（`supplierreturn`）：质量签批时才扣库存，流水类型 `RETURN_OUT`（`txn_type` 列只有 12 位，枚举名不能再长）。发起时先锁批次，额度 = 在库 - 待签批。对账的规则在 `SettlementService.unbilled`：结算前退的减数量，结算后退的生成负数冲减行（`return_id` 不为空）；算过的退货写 `settled_statement_id`，作废对账单时清空，这样既不重复也不漏。判断批次是否已结算要排除冲减行。
- 法规目录（`RegulatoryService`）：**系统不内置法规内容，不要凭记忆往代码或迁移里写法规数据**，只能导入，来源必填。按 INCI、别名和 CAS 号对照，对上多条取最严的（`strictest`）。物料 `cas_no` 保存时用 `domain/rules/CasNumber` 校验；目录 CAS 列只取有效号写进 `regulated_cas`（不唯一）。别名在 `regulated_alias`，对照键唯一；导入时整批删一次旧别名再写，`ruleOf`、`compare`、`violations` 都要查别名）。`key`：不分大小写和重音、去首尾空格（MySQL 的 `0900_ai_ci` 认为 é 和 e 相同，H2 不认为，只改一边就会把禁用条目悄悄覆盖）。导入先校验后写，不要在循环里查库（包括 `currentUser.find`、`audit.log`）：每次查询 Hibernate 都要检查全部已载入实体，曾 5000 行要 49 秒、预检也一样。`MasterService.check`、`PurchaseService`、`RfqService` 都用它，取严的。已生效配方不自动停用，走 `violations` 报紧急待办。合规资料（`ComplianceService`）对应配方版本，配方变了显示要重新评估；QSO 签批，不能是登记人。放行默认不拦，开关 `scm.compliance.require-docs-for-release`。这些默认规则在 `docs/PLM-默认规则.md`。
- 稳定性试验（`StabilityService`）：**条件和限度不内置**，前端预填的只是常用安排。所有写操作先锁试验行（`lockById`，事务第一句），所以录结果的接口路径要带试验 id，不能先按取样 id 查。结果提交后不能直接改，要走更正（`StabilityChangeService`，V26 迁移：QA/QSO 申请、另一位 QSO 批准，原值留在 `stability_correction`，按新值重判，改成不合格的生成偏差；有待审批的更正不能写结论）；方案可以加时间点、取消没做的时间点（`CANCELLED`）、加项目，每次记一版（`stability_amendment`），已有项目的限度不能改；更正审批先只查 `studyId` 再锁试验、再锁更正单；有上下限的由系统判定，人工填的合格不算数；不合格自动生成重大偏差挂到批次（`DeviationService.createForStudy`），偏差没关不能签批结论。保质期依据只看当前生效配方版本上已签批的结论。放行不拦。导出（报表 `stability`）走 `exportRows` 的全量查询。打印报告是纯前端页 `/stability-report?id=`（`StabilityReportPrint.vue`，不套主布局，写法同 `BatchRecordPrint.vue`），结果表每张最多 12 个时间点。默认规则在 `docs/PLM-默认规则.md`。
- 质量标准（`QualitySpecService`）：按物料分版本，QSO 签批生效，提交人不能签；同一物料只能有一个待签批，所以提交先锁物料行（事务第一句）。有生效标准的物料，放行前最近一次有效检验必须合格（`blocker`，物料检验放行和上市放行都调用），没有标准的照旧。检验录入、作废都先锁批次，所以接口路径带批次 id；放行也锁批次，两边互斥。不合格生成重大偏差（`DeviationService.createForInspection`）。判定共用 `domain/rules/SpecLimit`。期初导入不检查。标准行标了“可采用供方报告”（`coa_allowed`，V27 迁移）的项目可以照供应商或受托厂的报告录入，结果记来源（`ResultSource`），仍按限度判定；条件在 `QualitySpecService.coaBlocker`：只限采购和委托生产的批次、供方在合格名录内、有报告编号、批次或来源单据上有附件。标准可设连续采用的批数上限（`coa_max_streak`，默认不设），这个批数是按供方算出来的，所以 `record` 锁了批次、确认批次还没放行以后紧接着锁供方行，两把锁都要在普通查询之前；顺序固定是批次 → 供方（对账先锁供方、再碰已放行的批次，所以状态判断必须在锁供方之前）。没有锁物料行：库存流水同时引用物料和批次，外键的共享锁会和它死锁。取样要求只是文字，**不内置抽样方案**。导出（报表 `inspections`）走 `exportRows(from, to)`，按检验日期过滤。
- 新品立项（`NpdService`）：上市检查 `checks` 不另存，每次都从配方、备案、`ComplianceService`、`StabilityService`、`QualitySpecService`、标签设计稿（生效配方里至少一个包材有现行设计稿、没有要重新评审的）实时查，批准上市时再查一遍。一个成品只能属于一个进行中或已上市的项目，所以关联成品先锁物料行（事务第一句）。批准上市只是记录，不改成品和库存。立项由管理员批准（不要密码），上市由 QSO 签批。没有金额字段。
- 受控文件（`DocControlService`）和变更控制（`ChangeService`）：写操作先锁文件或变更行（事务第一句），所以签批修订、完成行动的接口路径要带文件或变更 id。修订没有附件（`OwnerType.DOC_REVISION`）不能签批；现行版本按生效日期算，不存状态。已读确认（`doc_ack`）按 `controlled_doc.train_roles` 算应确认的人，每人每版一次，确认也先锁文件。培训考核（V46 `doc_assessment`、`controlled_doc.assess_required`）由 QA/QSO 登记，要先确认已读、不能登记自己，以每人最近一次为准，登记也先锁文件。文件对应的制度（V48 `controlled_doc.procedures`，`domain/QualityProcedure` 22 项）的覆盖用 `plm/ProcedureCoverage` 算（纯 Java，受控文件、上线检查和 GMP 自查共用，不互相注入服务）。变更关闭要求行动全部完成、关联的配方版本已签批或放弃，申请人不能批准和关闭。配方草稿可关联实施中的配方或原料变更（`formula_version.change_id`），开关 `scm.change.require-for-formula` 打开后必须关联，默认关闭；关联时 `createDraft` 先锁变更再锁成品，和关闭变更互斥。供应商、标签等操作不联动。
- 标签设计稿（`ArtworkService`）：按包材分版本，记提交时的生效配方版本；提交时关联变更先锁变更行（V35 `label_artwork.change_id`，开关 `scm.change.require-for-artwork` 默认关闭，打开后第 2 版起必须关联已批准的标签、包材或配方变更），再锁包材行（同一包材只能一版待签批）；关联的设计稿待签批时变更不能关闭。批准要 QSO、不是提交人、评审清单全勾、有附件（`OwnerType.LABEL_ARTWORK`），且提交后配方没变；配方变了已批准的显示要重新评审。包材物料检验放行时（`ArtworkProofService.checkForRelease`）有现行设计稿就要确认印刷一致（`MaterialDecisionReq.artworkConfirmed`），采购收货的批次还要有这家供应商这一版的签样（`artwork_proof`，V25 迁移），设计稿要重新评审的不能放行；放行后批次记下 `artwork_id`、`proof_id`；不拦采购和收货。打样的写操作先锁设计稿行、再锁打样行，放行也锁设计稿行，和签样、作废、换版串行。
- GMP 自查（`qms/GmpSelfCheckService`，V47）：24 项定义在 `GmpChecklist`（条款号和类别按检查要点原文，要点是概括），结论用 `domain/rules/GmpJudgement`。同一时间只能一份进行，靠 `open_key` 唯一约束兜底，发起时先取单号锁住流水行（事务第一句）再查有没有进行中的；其余写操作先锁自查记录。存在瑕疵和不符合要转 CAPA（`CapaSource.SELF_CHECK`）才能提交，签批人不能是提交人。`GmpEvidenceService` 只读，按期间批量查，新加核对不要整表读。
- 附件只增不删，存储用 `CREATE_NEW` 不覆盖；上传校验白名单扩展名和文件头，记录 SHA-256，下载时重新核对。文件在 `scm.storage.dir`（Docker 里是 `files-data` 卷），数据库只存元数据。受托厂上传走 `/api/portal/oem/orders/{moId}/attachments`，只能传本厂未完工的单子。受托厂能看到内部传到本单的附件，这是用户确认过的，不是漏洞。
- `spring.servlet.multipart` 的上限必须高于 nginx 的 `client_max_body_size`（现在 30 MB 对 25 MB）。业务上限（20 MB）在 `AttachmentService` 判断。反过来配置会让后端没读完请求体就回错误，和 nginx 互相等待到超时。
- 金额是标准成本估值：`MasterService.unitCosts()` 优先用标准成本，成品退回 BOM 物料成本；没成本的物料不计入，并显示数量提示。不要当账面成本用。
- 配方：`bom` / `bom_line` 永远是当前生效版本的内容，其它模块照常读。生效配方不能直接改（`MasterService.saveBom` 只接受首版），要走 `FormulaService`：草稿 → 提交 → QSO 签批，发起人不能批自己的；原料变化必须有该版本 FILED 的 `product_filing`。生产单 `formula_version_id`：未发料跟随生效版本，发料后锁定，用量按版本读（`MasterService.versionLines`）。
- 备案编号：首次可在主数据或 CSV 里填（同时生成一条 FILED 记录，便于上线初始化）；有已完成的记录后只能走 `FilingService`，`MasterService.updateItem` 拒绝直接修改。
- 放行的前置检查在 `QualityService`：批次有未关闭的重大及以上偏差、注册证过期、用到的物料批次被冻结或判定不合格，都不能放行。放行时写 `retain_sample`，这是留样台账的唯一入口。
- 批记录（`MesService`）只对自产且发料时有工艺路线的单子生效，在发料事务里固化。`ProductionService.complete` 先查 `mes.missing()`，并把这张单的偏差挂到产出批次。工序记录提交后不提供修改接口。过程检验超限不阻止生产，但必须自动生成偏差。
- 签批、复核、验证类操作的执行人不能是操作人本身（CAPA 完成人、工序操作人、配方发起人），规则写在服务层，不靠界面隐藏。
- 导出在服务端生成（`ReportService` + `common/XlsxWriter`），每次写审计。单元格文本只写内联字符串，不写公式。新增报表在 `registry()` 里加一项。预测规则在 `domain/rules/DemandForecast`，历史按首次出库到上个完整月。
- 提醒内容直接取 `DashboardService.summary()` 的待办（全部），总览页面经 `forDisplay` 每类最多显示 30 条，`todoTotal` 是全部数量。总览没有缓存（放行后待办要立刻消失），新加的待办不要把整张表读出来再过滤。新的到期规则加到总览待办就会进提醒，新的待办类型要在 `TodoRouting` 里指定岗位，否则不进个人邮件。错误信息写库前要去掉 webhook 地址（含密钥）。
- 生产单取消（`ProductionService.cancel`）：未发料直接取消；已发料未开工退料并删除 `mfg_issue`（否则追溯会显示批次流向这张单，流水和审计保留）；开工后（`mes.started`）或受托厂已回传生产中、已申报的不能取消。新增读生产单状态的代码要想到 CANCELLED。采购单：没收货 CANCELLED，部分收货 CLOSED。
- 主数据导入：已存在的编码，留空的格和文件里没有的列都保留原值（曾因留空把禁用原料悄悄解禁、清空标准成本），要清空到主数据页面改；预检列出每一处修改。是否类的格认不出就报错，不当成“否”。模板里新加列时，`/items`、`/partners` 的返回要有同名字段，否则留空还是会被清空；新列放在模板最后，已经下载过旧模板的人原有各列位置不变（导入按表头名对应，旧模板照样能导），空库走查会核对模板表头，要同步改。
- 现有库存只能走期初导入（`OpeningStockService`，来源 `SourceType.OPEN`，流水 `OPENING`），不要造采购单或生产单。已放行的批次写放行记录，成品写留样，所以只给 QSO 并要签批。行上不加 `@Valid`，缺字段要按 Excel 行号报。
- 工序勾了“须记录设备”时，`MesService.recordStep` 用 `EquipmentService.unusableReason` 校验；校准不合格的处理在 `EquipmentService.calibrate`，设备停用并生成偏差。
- 导出（`ReportService`）必须用 `listAll` 类查询，不能用页面的 Top300 查询；超过 `MAX_ROWS` 报错，不截断。页面列表到上限时用 `ListCapHint` 提示。
- 登录失败计数要落库，`LoginService.login` 对 BizException 不回滚；密码规则统一走 `PasswordPolicy`（BCrypt 只认 72 字节，字符上限不够，汉字要算字节）。账号不存在也要算一次密码（否则响应时间能试出账号），同一来源每分钟失败 20 次限流（`ClientRateLimiter`，只认 nginx 覆盖的 `X-Real-IP`，后端端口不能对外）。`DeploymentSecretsCheck` 让 prod 配置拒绝用占位符和默认值启动，`.env.example` 改占位符时要同步。 登录成功、失败和锁定都写审计，这时还没有登录身份，用 `AuditService.logAs`。
- 委托生产完工前必须确认受托厂已出厂放行；实际产量超出计划 95%–102% 必须填物料平衡说明；上市放行必须登记留样，保存期 = 有效期 + 6 个月。
- 供应商 / 受托厂不在合格名录或资质过期，不能下单；禁用原料不能采购、不能进 BOM；限用原料按净含量校验浓度上限。
- 追溯有标准答案的回归在 `TraceRecallDrillTest`，改流向相关的逻辑（发料、完工、出库、退货、召回）要跑它。供应商和受托厂只给得出自己的批号，追溯页查不到系统批号时走 `TraceService.search`。
- 示例数据只通过业务服务写入（`DemoDataLoader`），日期相对“今天”生成，名称带“（示例）”。

## 代码约定

- JSON 请求里的文字由 `common/JacksonConfig` 统一去掉控制字符（保留制表符和换行）。CSV 导入走 JSON，也会过滤。
- 面向用户的错误用 `BizException`，消息写中文、说清怎么改；不要把异常堆栈返回前端。
- 录入的数量加 `@Qty`（DECIMAL(18,4)，超出小数位数据库会悄悄四舍五入，0.00001 会存成 0），其它小数用 `@Digits` 对齐列定义。`@Size` 不能超过实际写入的列宽，一个字段写进多张表时按最短的算（备案编号曾因此报错）。校验默认提示靠 `GlobalExceptionHandler.FIELD_LABELS` 翻译字段名，新字段要加；注解写了中文 message 的直接显示。
- 前端日期用 `utils/format.js` 的 `today()`、`daysFromToday()`，都按上海算，和后端 `TimeProvider` 一致；日期选择器的下限用 `startOfBusinessDay()`。不要用 `toISOString`（按 UTC 算，北京时间早上 8 点前会少一天），也不要按浏览器本地日期算（电脑时区比上海早时，默认生产日期是明天，后端会拒绝）。
- 令牌失效时前端不跳转登录页，由 `ReloginDialog` 就地要求输入密码（默认 12 小时过期，跳走会丢掉正在填的表单）。登录请求不能带令牌，失效令牌会让登录直接 401。
- DTO 用 Java record，放在对应 Service 或 `XxxDtos` 里；请求参数用 jakarta validation 注解。
- 金额 / 数量一律 `BigDecimal`，比较用 `compareTo`；数据库数量列 DECIMAL(18,4)，计算需求量时 `setScale(4, RoundingMode.UP)`。
- 时间取 `TimeProvider.today()/now()`（Asia/Shanghai），不要直接 `LocalDate.now()`。
- 前端文案用中文，按钮写清动作（“确认收货”“签批”），不要用“提交”“确定”这种泛词。

## 当前状态（2026-11-18）

- 本地整理验证：后端 227 项按逆序和随机顺序各通过一次，前端 `npm run build` 通过，Playwright 冒烟测试 71 项全部通过（含纸质副本、设计稿关联、axe 无障碍和手机宽度检查）。空库走查 15 项是此前验证记录，本次未重新执行。GitHub Actions 在 PR 上跑后端、构建、冒烟和空库走查四项。
  - 后端测试类共用一个内存库，CI 上测试类的执行顺序不固定。断言示例数据刚写完时的状态（如待检正好 1 批），或者会留下影响全局检查的数据（如没有配方的成品）的测试类，要用 `@TestPropertySource` 换一个单独的库（见 `MainLineIntegrationTest`）。main 曾因此失败。改测试后用 `-Dsurefire.runOrder=reversealphabetical` 和 `random` 各跑一遍。
  - `Item.netG` 需要显式 `@Column(name = "net_g")`：以单个大写字母结尾的字段名默认会映射错。测试配置开启了 `ddl-auto: validate`。
  - Lombok 钉在 1.18.48（旧版在 JDK 27 上编译崩溃）。Spring Boot 3.5.16。判断“某 JDK 能编译”时务必 `mvn clean`。
  - `pom.xml` 把 Tomcat、Jackson、log4j 钉在修复了已知漏洞的版本，升 Spring Boot 时要删掉这几行。Spring MVC 6.2.19 还有两条 OSV 记录（SSE 片段渲染、XsltView），修复只在 7.x，系统没用到这两个功能。扫描脚本 `scripts/dep-scan.py`，判断过不影响的漏洞登记在 `scripts/dep-scan-allow.txt`，`dep-scan.yml` 每周跑一次（不是 PR 检查）。
- 已完成：受控文件对应制度和制度覆盖（V48 迁移）；GMP 自查（检查要点委托生产版，V47 迁移）；受控文件培训考核（V46 迁移）；标签设计稿关联变更控制（V35 迁移）；受控文件纸质副本发放与回收（V36 迁移）；检验项目可采用供方报告、取样记录（V27 迁移）；新品上市检查加包材设计稿、批次检验结果导出；稳定性方案修改和结果更正（V26 迁移）；包材打样签样和印刷批次记录（V25 迁移）；稳定性试验报告打印页；物料 CAS 号和法规目录按 CAS 对照（V24 迁移）；法规目录别名（V23 迁移）；包材放行核对设计稿；稳定性趋势和结果导出；标签设计稿评审（V22 迁移）；受控文件已读确认（V21 迁移）；配方变更关联变更控制（V20 迁移）；受控文件和变更控制（V19 迁移）；新品立项和试制（V18 迁移）；质量标准和批次检验（V17 迁移）；稳定性试验（V16 迁移）；原料法规目录、产品合规资料（V15 迁移）；退货给供应商（V14 迁移）；询价比价、对账结算、财务角色（V12、V13 迁移）；供应商门户、准时交货率和综合评分（V11 迁移）；一期主线；库存作业、销售退货、投诉与召回、主数据 CSV 导入；代工厂门户、经销商门户、电商对接骨架；登录锁定、密码策略；补货建议、一物一码、经营看板（V4 迁移）；标准成本与看板金额、附件管理（V5 迁移）；批次标签打印和扫码核对；配方版本与备案、偏差与 CAPA、供应商审核、留样台账、自产批生产记录、Excel 导出、每日提醒（V6 迁移）；单据取消和关闭、全量导出、提醒按岗位分发、账号邮箱（V7 迁移）、带季节性的需求预测；期初库存导入、设备台账与校准（V8 迁移）；配方批量导入、上线检查页；Liquid Glass 界面；HTTPS 叠加配置、自动备份 / 恢复脚本、健康检查；`docs/csv/` 验证文档包。
- 在 Claude Code 云端会话里跑测试要注意三件事（开发机上没有这些问题）：预装的 Chromium 和项目钉的 Playwright 版本对不上，不要重装也不要改仓库里的配置，在临时目录写一个包装配置，导入 `playwright.config.js` 后给 `projects[].use.launchOptions.executablePath` 指 `/opt/pw-browsers/chromium`，`testDir`、`webServer.cwd` 换成绝对路径；系统没有 UTF-8 locale，中文下载文件名会变成 `download`，跑之前设 `LC_ALL=C.UTF-8`；Docker 要手动启动 `dockerd`，之后能拉 `mysql:8.0` 做 MySQL 实测（V27 就是这样测的，先用上一版的 jar 建库再升级）。
- Docker 部署已在本机 Colima（profile `cosmetic-scm`）上完整演练过：MySQL 8 上迁移和示例数据都正常，HTTPS、首次改密、备份恢复、数据库宕机时的健康检查都实测通过，记录见 `docs/csv/07-部署预验证记录-2026-11-03.md`（含历次升级、备份恢复、并发、容量测试和空库上线演练）。所有自动化测试都带示例数据，改动上线流程后要在空库上再走一遍。并发、死锁和 CSP 这类问题 H2 和开发服务器测不全，改到这些要到 MySQL 上跑。CSP、Permissions-Policy 和上传限制只有经过 nginx 才生效，开发服务器测不出来，改这些要走一遍 Docker。这台 Colima 没有共享宿主机目录，绑定挂载会是空目录；里面还有一套用 8088 端口的 `cosmetic-scm` 旧部署，不要动它。
- 打印：`styles.css` 的 `@media print` 把卡片、表格的外层改成块级、不裁剪。外层只要有 `overflow: hidden` 或 flex，跨页时末尾的行会被裁掉且没有提示（120 个客户曾只印出 98 个）。Playwright 读不出 PDF 文字，改打印样式后用 PyMuPDF 数一遍行数。
- 看板图表配色用 dataviz 参考调色板，已用校验脚本在浅色、深色下验过；图表是纯 HTML/CSS，没有引入图表库。
- 示例数据含：精华液 V2 配方待签批且备案在途、面霜工艺路线、明瓶包装审核快到期、电子秤校准快到期、一条微小偏差和 CAPA；老批次精华液（效期门槛、呆滞）、退货隔离区 + 待处置退货、待上报的不良反应投诉、受托厂已排产的委托单、经销商待审核订单、一张缺货失败的电商订单、第一批精华液的 20 个防伪码、精华液长期和面霜加速两个稳定性试验（面霜的取样快到期）、角鲨烷已生效（含量可采用供方报告、有取样要求）和面霜待签批的质量标准、面膜待批准和面霜开发中的新品项目、一份待签批的 SOP、一项待评估的供应商变更、面霜瓶待评审的标签设计稿。

- `docs/操作手册.md` 是给使用者看的，改了按钮名称、流程或提示要同步。对应的走查在 `frontend/e2e-empty/go-live.spec.js`，用例之间依赖前面留下的数据，不要单独重试。

## 下一步（按优先级）

1. 在正式服务器上部署，按 IQ 逐项核对（开发机上已演练通过），按 SOP-09 切换；配置 `./backups` 异地同步；用现场的标签打印机和扫码枪执行 OQ-32（还没有用真实硬件测过）。
2. 用真实 SKU、供应商、代工厂、客户替换示例主数据（CSV 导入），给受托厂和经销商开门户账号；质量部按 `docs/csv/` 执行 OQ / PQ 并签字。
3. 选定电商 ERP（聚水潭、旺店通、吉客云等）后，在 `integration` 包里写报文适配和回调 / 定时拉取。
4. 用现场工艺替换示例工艺路线，用真实邮箱和企业微信群验证提醒。
5. SRM 的默认规则（`docs/SRM-默认规则.md`）要采购、财务、质量部确认。账期与退款登记已实现，财务系统对接仍待完成。PLM 的主要部分都做了，剩下的见 `docs/PLM-默认规则.md` 的“没有做的”；法规目录内容要质量部按官方版本导入。
6. 需要时：预测加促销等人工调整；实际成本核算对接财务系统；称量数据采集；即时提醒。
