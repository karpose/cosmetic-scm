# PLM 默认规则

产品合规资料、原料法规目录和稳定性试验的规则是我先定的默认值，等质量部确认后再改。这份清单列出每条规则现在的取值，以及要改时看哪里。

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

## 没有做的

- 新品立项、小试和中试、质量标准、受控文件和变更控制。
- 稳定性：方案修改和版本、结果更正流程、趋势图、导出和打印报告。
- 标签图稿的比对、版本和印刷批次的关联。
- 自动获取法规更新。
