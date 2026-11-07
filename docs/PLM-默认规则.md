# PLM 默认规则

产品合规资料、原料法规目录、稳定性试验、质量标准和新品立项的规则是我先定的默认值，等质量部确认后再改。这份清单列出每条规则现在的取值，以及要改时看哪里。

**状态**：都还没有经过质量部确认。

## 法规目录内容不是系统提供的

**系统不内置任何法规内容。** 目录要由质量或法规人员按官方发布的版本导入，并写明来源。示例数据里的条目只是为了演示功能，来源写着“示例数据，不是法规原文”，**上线前要停用**。

上线检查页会提示目录是否为空。法规更新时要重新导入，系统不会自动获取。

## 法规目录

| 规则 | 现在的做法 | 要改时看 |
| --- | --- | --- |
| 怎么对照 | 只按 INCI 名称，不分大小写、不分重音（é 和 e 算同一个），去首尾空格。不按 CAS 号和中文名 | `RegulatoryService.key` |
| 没填 INCI 的物料 | 对不上，不会被校验，列在对照结果里 | `RegulatoryService.compare` |
| 目录和物料不一致 | 取严的：目录禁用就按禁用，上限取小的 | `RegulatoryService.isBanned`、`effectiveLimit` |
| 哪里会拦 | 配方保存和提交、采购下单、发起询价 | `MasterService.check`、`PurchaseService`、`RfqService` |
| 已生效的配方 | 不会自动停用，总览报紧急待办，上线检查报必须处理 | `RegulatoryService.violations` |
| 使用条件 | 只是记录，系统不判断（如“仅用于淋洗类”），只校验浓度上限 | `RegulatoryService` |
| 谁能导入和停用 | QA、QSO。管理员不行，因为它决定配方能不能过校验 | `RegulatoryController` |
| 导入 | 全成或全不成，来源必填；INCI 已存在的按整行覆盖，**留空的上限就是无上限**，不保留原值。预检列出类别、上限、状态的变化，审计也记下 | `RegulatoryService.importRows` |
| 不再适用的 | 停用，不删除 | `RegulatoryService.retire` |

## 产品合规资料

| 规则 | 现在的做法 | 要改时看 |
| --- | --- | --- |
| 资料类型 | 安全评估、功效宣称依据、标签 | `ComplianceDocType` |
| 对应什么 | 配方版本。默认当前生效版本，可以先为草稿或待签批版本做评估 | `ComplianceService.create` |
| 配方变了 | 旧版本的资料显示“要重新评估”，总览提醒 | `ComplianceService.state` |
| 谁能登记 | 主数据、QA、QSO | `ComplianceController` |
| 谁能签批 | 只有 QSO，不能是登记人，要输入密码 | `ComplianceService.approve` |
| 必须有的 | 安全评估和标签。功效依据不是每个产品都要，缺了只显示未登记 | `ComplianceService.missingFor` |
| 报告内容 | 系统不判断对不对，只管有没有、谁签批、对应哪个版本 | — |
| 上市放行 | **默认不拦**，开关 `SCM_COMPLIANCE_REQUIRE_DOCS=true` 后才拦。一上线就拦的话，所有现存产品都放不了行，资料补齐后再打开 | `QualityService.marketRelease` |
| 放行看哪个版本 | 生产单发料时锁定的配方版本；期初批次看当前生效版本 | `QualityService.marketRelease` |

## 全成分表

只是**草稿**，不能直接用于标签：

- 配方里的原料按含量从高到低，含量不超过 0.1% 的标出来，排序规则要按现行法规核对。
- 复配原料没有拆成单一成分。
- 配方里没录的成分（如水）不在里面。
- 显示的是物料名称，不是标准中文名称。

## 稳定性试验

**系统不内置试验条件和合格标准。** 登记页预填的时间点和检测项目只是常用安排，不是法规要求，条件和限度都由质量部按公司方案填。

| 规则 | 现在的做法 | 要改时看 |
| --- | --- | --- |
| 一次试验是什么 | 一个样品放在一个条件下。多个条件、多个批次就登记多次 | `StabilityService.create` |
| 样品 | 填批号或写明样品来源。批次是生产单做的，只能作为当时配方版本的依据 | `StabilityService.version` |
| 开始日期 | 不能晚于今天，可以往前填，用来补录已有的试验 | `StabilityService.create` |
| 方案能改吗 | 登记后不能改时间点和项目，要改就取消重新登记 | `StabilityService` |
| 谁能登记 | 主数据、QA、QSO | `StabilityController` |
| 谁能录结果、写结论、取消 | QA、QSO | `StabilityController` |
| 计划日期 | 开始日期 + 月数 + 天数，月底按当月最后一天（1 月 31 日加 1 个月是 2 月 28 日） | `StabilityService.create` |
| 取样窗口 | 计划日期前后 7 天。早于窗口不接受，时间不够的结果不能作依据；晚于窗口要写原因 | `StabilityService.WINDOW_DAYS` |
| 判定 | 填了上下限的由系统判定，人工填的合格不算数；没填的由检测人判定 | `StabilityService.judge` |
| 结果能改吗 | 提交后不能改，录错了在偏差里说明 | `StabilityService.pendingPull` |
| 不合格 | 要写现场情况，自动生成**重大**偏差并挂到批次上。批次还没放行的，偏差关闭前放不了行；**已放行的不会自动冻结或召回**，由质量部判断 | `DeviationService.createForStudy` |
| 做不了的时间点 | 登记未取样并写原因 | `StabilityService.skip` |
| 写结论 | 所有时间点都处理完、至少有一次结果才能写；支持的保质期由人填，系统不判断对不对 | `StabilityService.conclude` |
| 签批 | 只有 QSO，不能是提交结论的人，要输入密码；有未关闭的偏差不能签 | `StabilityService.approve` |
| 保质期依据 | 看当前生效配方版本上已签批的结论，取最长的，和物料上的保质期比。**加速和长期都算**，加速能外推多久由写结论的人负责 | `StabilityService.coverage` |
| 配方变了 | 旧结论不再算依据，显示要重新做 | `StabilityService.coverageState` |
| 提醒 | 取样提前 7 天提醒，超期算紧急；待写结论、待签批、依据不足都进总览。**从没做过的不进总览**，只在上线检查提示，上线初期不要刷屏 | `DashboardService` |
| 上市放行 | **不拦**。依据不足只提醒 | — |

## 质量标准和批次检验

**系统不内置任何标准值。** 项目、方法和限度都由质量部按公司标准填写。

| 规则 | 现在的做法 | 要改时看 |
| --- | --- | --- |
| 按什么建 | 每个物料（原料、包材、成品）一套，每次修改是一个新版本 | `QualitySpecService.create` |
| 谁能提交 | 主数据、QA、QSO。同一物料同时只能有一个待签批的版本 | `QualitySpecController`、`create` |
| 签批 | 只有 QSO，不能是提交人，要输入密码。签批后生效，旧版本自动变为“已替代” | `QualitySpecService.approve` |
| 没有标准的物料 | 照旧，按检验报告编号放行 | `QualitySpecService.blocker` |
| 有标准的物料 | 放行前要逐项录入结果，**最近一次有效检验合格**才能放行。物料检验放行和成品上市放行都适用 | `QualitySpecService.blocker`、`QualityService` |
| 按哪个版本检验 | 录入时的生效版本。之后标准更新了，已有的检验记录照样有效，不要求重检 | `QualitySpecService.record` |
| 判定 | 填了上下限的由系统判定，人工填的合格不算数；没填的由检验员判定 | `QualitySpecService.judge` |
| 检验日期 | 不能晚于今天，不能早于生产日期 | `QualitySpecService.record` |
| 不合格 | 要写情况，自动生成**重大**偏差挂到批次，偏差关闭前放不了行 | `DeviationService.createForInspection` |
| 复检 | 有不合格记录后可以复检，要写原因和依据；复检合格还要等偏差关闭 | `QualitySpecService.record` |
| 结果能改吗 | 不能改。录错了由 QSO 作废（写原因，记录保留）后重录；放行或拒收以后不能作废 | `QualitySpecService.voidInspection` |
| 拒收 | 不受检验记录限制 | `QualityService` |
| 期初导入的批次 | 不检查（期初导入直接写放行记录） | `OpeningStockService` |
| 稳定性试验 | 登记时可以从成品的生效标准带入项目，带入后要按试验方案核对限度 | `Stability.vue` |

## 新品立项和试制

| 规则 | 现在的做法 | 要改时看 |
| --- | --- | --- |
| 谁能申请立项 | 管理员、主数据、QA、QSO | `NpdController` |
| 谁批准立项 | 管理员，不能是申请人。不要求输入密码（这是经营决策，不是质量签批） | `NpdService.approve` |
| 计划上市日期 | 申请时不能早于今天；过期没上市的进总览提醒 | `NpdService.create`、`late` |
| 关联成品 | 成品先在主数据建好。一个成品只能属于一个进行中或已上市的项目，取消的项目会释放成品 | `NpdService.link` |
| 试制记录 | 小试、中试；中试必须选配方版本，配方版本要属于关联的成品。记录后不能改 | `NpdService.addTrial` |
| 谁能记录试制 | 主数据、QA、QSO、生产 | `NpdController` |
| 上市检查 | 7 项，都从已有模块实时查，不另存：关联成品、生效配方、备案或注册（类型要和立项一致，注册证没过期）、当前配方版本的安全评估和标签已签批、稳定性依据够、成品质量标准生效、当前生效配方版本有一次中试通过 | `NpdService.checks` |
| 提交上市 | 7 项都通过才能提交 | `NpdService.submitLaunch` |
| 批准上市 | 只有 QSO，不能是提交人，要输入密码；**批准时重新检查一遍**，提交后资料被作废之类的会被拦住 | `NpdService.approveLaunch` |
| 批准上市的作用 | 只是记录，不改成品、配方和库存。成品能不能卖仍由批次放行决定 | — |
| 取消 | 管理员、主数据，上市以前都能取消，记录保留 | `NpdService.cancel` |

## 没有做的

- 受控文件和变更控制。
- 新品：项目预算和成本（涉及价格权限，没有做）、阶段计划和甘特图、包材设计稿评审、多个成品规格（同一项目多个 SKU）。
- 质量标准：取样计划和抽样数量、按供应商 COA 免检、检验项目分级（全检、抽检）、检验结果导出。
- 稳定性：方案修改和版本、结果更正流程、趋势图、导出和打印报告。
- 标签图稿的比对、版本和印刷批次的关联。
- 自动获取法规更新。
