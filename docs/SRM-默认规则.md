# SRM 默认规则清单

询价比价和对账结算是按常见采购规范先定的默认规则，等业务确认后再改。这份清单列出每条规则现在的取值，以及要改时看哪里。

**状态**：都还没有经过采购部、财务部和质量部确认。

## 询价比价

| 规则 | 现在的做法 | 要改时看 |
| --- | --- | --- |
| 比价最少家数 | 3 家。不足 3 家能定标，但要写原因 | `RfqService.MIN_QUOTES` |
| 没选最低价 | 能定标，但要写原因 | `RfqService.award` |
| 比价依据 | 只比含税单价，不折算税率、交期和账期 | `RfqService.award`、`views` |
| 封标 | 截止日期前，内部接口不返回价格，审计日志也不记价格 | `RfqService.sealed`、`submitQuote` |
| 截止当天 | 当天还能报价，第二天才算截止 | `RfqService.sealed` |
| 提前开标 | 邀请的供应商都报完所有行才可以 | `RfqService.open` |
| 定标人 | 不能是询价人，要输入签批密码 | `RfqService.award` |
| 谁能发起和定标 | 管理员、采购。财务只能查看 | `RfqController` |
| 报价修改 | 截止前可以改，不留历史价格 | `RfqService.submitQuote` |
| 报价有效期 | 不能早于截止日期；过期的报价不能定标 | `RfqService.submitQuote`、`award` |
| 供应商看到的结果 | 只看到“已中标”或“未中标”，看不到别家价格和定标原因 | `RfqService.portalViews` |
| 按行定标 | 每行可以定不同的供应商，一家一张采购单；可以只定一部分，有报价却不定要写原因 | `RfqService.award` |
| 采购单期望到货日 | 今天 + 该供应商最长的交货天数 | `RfqService.award` |

## 对账结算

| 规则 | 现在的做法 | 要改时看 |
| --- | --- | --- |
| 哪些批次能结算 | 只有**已放行**的。待检、不合格、冻结的都不结算 | `SettlementService.unbilled` |
| 结算数量 | 批次入库数量。之后发料、报废多少都和供应商无关 | `SettlementService.unbilled` |
| 金额 | 入库数量 × 含税单价，每行四舍五入到分 | `SettlementService.unbilled` |
| 税额 | 含税金额 × 税率 /（100 + 税率）。默认税率 13%，只是页面默认值 | `SettlementService.unbilled` |
| 没有单价的批次 | 不进对账单，生成时列出来提醒 | `SettlementService.unbilled` |
| 调整金额 | 扣款填负数，必须写原因；调整后合计要大于 0 | `SettlementService.create` |
| 发票 | 金额必须**完全等于**对账单合计，没有容差；一张对账单一张发票；同一供应商发票号不能重复 | `SettlementService.invoice` |
| 审批人 | 不能是制单人，要输入签批密码 | `SettlementService.approve` |
| 谁能做什么 | 采购生成和作废，财务审批和付款，管理员都可以 | `SettlementController` |
| 付款 | 可以分多次，不能超付，只做登记 | `SettlementService.pay` |
| 作废 | 审批前可以作废，批次放回去重新结算；审批后不能作废 | `SettlementService.cancel` |
| 改单价 | 进了有效对账单的不能改；改已有单价要写原因 | `PurchaseService.setPrices` |
| 批次后来出问题 | 生成对账单后批次被冻结或判不合格，不能审批和付款，要等质量结论或作废重来 | `SettlementService.requireStillReleased` |
| 谁能看价格 | 采购、财务、管理员。其它岗位：接口不返回单价；审计记录遮盖成“含金额”，也搜不到；对账单附件看不了；总览不带金额。供应商只看自己的 | `PriceAccess`、`AuditService.visible` |

## 供应商评分和提醒

| 规则 | 现在的做法 | 要改时看 |
| --- | --- | --- |
| 评分权重 | 质量 50、交货 30、审核 20，没有数据的项不计 | `SupplierScore` |
| 等级线 | A ≥ 90，B ≥ 75，C ≥ 60，其余 D | `SupplierScore` |
| 评分的作用 | 只作参考，不影响下单，不给供应商看 | `SupplierAuditService.toRow` |
| 准时率 | 按第一次承诺的交期算，没承诺过看采购期望 | `PurchaseOrderRepository.deliveryStats` |
| 交期提醒 | 还有 3 天以内 | `NotifyService.SUPPLIER_DUE_WARN_DAYS` |

## 没有做的

要做的话，这些都不是改一个参数能解决的，需要先确定业务规则：

- **退货给供应商**：系统没有退供流程。不合格批次不结算；已放行、已付款后才发现问题的，只能用调整金额扣款。
- **账期和付款计划**：没有账期，也不提醒什么时候该付。
- **多币种、多税率拆分**：只支持人民币；同一发票多税率时只看总额。
- **红字发票、一票多单、多票一单**：不支持。
- **预付款、应付账款、记账凭证**：这不是财务系统，也不和财务系统对接。
- **框架协议价、价格历史分析**：单价只记在每张采购单上。
- **多轮议价、在线竞价**：只有一轮报价，截止前可以改。
- **询价的物料范围**：只能询原料和包材，不能询加工费。
