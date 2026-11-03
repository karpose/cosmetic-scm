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

# 整体部署
cp .env.example .env && docker compose up -d --build
```

示例账号（仅 dev）：admin / Admin@123456；purchase1、prod1、qa1、qso1、sales1、viewer1 / Demo@12345。

## 架构

- 后端 `backend/src/main/java/com/cosmetic/scm/`，按业务模块分包：master、purchase、inventory、production、quality、sales、trace、dashboard、audit、security、demo。
- `domain/rules/` 是纯 Java 业务规则（FefoAllocator、ShelfLife、FormulaCompliance），不依赖框架，有单元测试。新的计算规则优先放这里。
- 实体之间只用 Long 外键字段，不用 JPA 关联映射；列表视图通过 `MasterLookup` 一次载入主数据 Map 再组装 record 视图。
- 表结构只由 Flyway 管理（`ddl-auto: none`）。改表新增 `V{n}__xxx.sql`，**绝不修改已有迁移脚本**。SQL 需同时兼容 MySQL 8 和 H2 MySQL 模式。
- 前端 `frontend/src/`：Vue 3 `<script setup>` + Element Plus，JavaScript（非 TS）。接口统一走 `src/api.js`（自动带 JWT，错误统一 `ElMessage` 提示，`{ silent: true }` 可静默）。主数据下拉用 `utils/refdata.js` 的 `useRefData()`。

## 必须保持的规则

- 库存只能通过 `InventoryService.change/consume` 修改：行锁 + 不允许负数 + 每次写 `stock_txn` 流水。
- 所有业务写操作在一个 `@Transactional` 方法里完成，并在同一事务里调用 `AuditService.log`。审计日志只增不改，不提供修改 / 删除接口。
- 单号、批号由 `DocNoService.next(prefix, date)` 生成（必须在事务内）。
- FEFO 分配只取 `RELEASED` 批次；出库时再次确认批次状态（防止分配后被冻结）。
- 成品上市放行接口只授予 `QSO` 角色（管理员也不行）；放行、拒收、冻结、解冻都通过 `SignatureService.verify` 要求再次输入密码。
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

- 代码在云端工作区写成，那里无法访问 Maven 和 npm 源，所以**完整工程还没真正编译和运行过**。已做过的验证：
  - `domain/rules` 用 javac 编译并跑过 18 项规则测试，全部通过；
  - 其余后端代码在“框架类用桩代替 + Lombok 展开”的条件下 javac 编译通过（SecurityConfig 只做了人工检查）；
  - 前端只做了脚本语法检查和模板标签配对检查，没有 `vite build` 过。
- **第一件事**：跑 `mvn test` 和 `npm install && npm run build`，修掉所有编译和测试问题，再用示例账号把 README 第一节的 5 步流程在浏览器里走一遍。

## 下一步（按优先级）

1. 跑通构建、测试和端到端流程（见上）。
2. 补前端交互细节：分页、表格筛选、错误态；为关键页面加组件测试或 Playwright 冒烟测试。
3. 用真实 SKU、供应商、代工厂替换示例主数据（提供 Excel 导入）。
4. 二期：电商平台订单 / 库存对接（按选定的电商 ERP）、代工厂协同门户、经销商订货门户。
5. 上线前：登录失败锁定、密码策略、HTTPS、数据库备份、计算机化系统验证（CSV）记录。
