# SRM 默认规则（V28 已升级）

## 询价比价
- **比价最少家数**：3 家。不足 3 家能定标，但要写原因（`RfqService.MIN_QUOTES`）
- **没选最低价**：能定标，但要写原因（`RfqService.award`）
- **比价依据与历史采购价参考**：按含税单价排序对比，同时自动关联展示该物料最近一次有效采购单单价（`lastPoPrice`）及涨跌幅百分比（`RfqService.views`）
- **多轮议价（V28 新增）**：已开标或已截止待定标时，若报价偏高，采购可设定新截止日期与议价说明发起下一轮议价（`POST /api/rfqs/{id}/next-round`），轮次 `roundNo + 1`，原报价自动快照至 `prevUnitPrice` 供对比，并重新进入封标报价状态（`RfqService.nextRound`）
- **询价物料范围（V28 扩展）**：支持原料（`RM`）、包材（`PKG`）及半成品委外加工（`WIP`）询价（`RfqService.create`）
- **封标**：当前轮次截止日期前，内部接口不返回价格，审计日志也不记价格（`RfqService.sealed`、`submitQuote`）
- **提前开标**：邀请的供应商在本轮次都报完所有行才可以（`RfqService.open`）
- **定标人**：不能是询价人，要输入签批密码（`RfqService.award`）

## 对账结算
- **哪些批次能结算**：只有**已放行**的。待检、不合格、冻结的都不结算（`SettlementService.unbilled`）
- **账期与应付到期日（V28 新增）**：生成对账单时可设置信用账期天数 `creditDays`（默认 30 天，范围 0~365 天），自动计算应付到期日 `dueDate = periodTo + creditDays` 并计算逾期天数 `overdueDays`（`SettlementRule.computeDueDate`、`overdueDays`）
- **多税率自动拆分汇总（V28 新增）**：对账单预览与详情自动按税率升序拆分汇总各档税率的含税金额、不含税金额和税额（`SettlementRule.breakdownByTaxRate`）
- **一单多票与蓝/红字发票（V28 新增）**：支持单张或多张发票组合登记（`POST /api/statements/{id}/invoices`），区分蓝字发票（`BLUE`，正数）与红字冲减发票（`RED`，负数），发票含税总额须严格等于对账单合计（`SettlementRule.validateInvoices`）
- **红字冲减对账单与供应商退款（V28 新增）**：当结算后退货冲减超过当期新入库货款（`totalAmount < 0`）时，支持勾选“允许生成红字冲减对账单”，由供应商登记红字发票，财务审批后通过 `POST /api/statements/{id}/refund` 登记供应商退款（`SettlementService.refund`）
- **审批人**：不能是制单人，要输入签批密码（`SettlementService.approve`）
- **谁能看价格**：采购、财务、管理员。其它岗位接口不返回单价，审计记录遮盖成“含金额”

## 供应商评分和提醒
- **评分权重**：质量 50、交货 30、审核 20，没有数据的项不计（`SupplierScore`）
- **等级线**：A ≥ 90, B ≥ 75, C ≥ 60, 其余 D（`SupplierScore`）
- **准时率**：按第一次承诺的交期算，没承诺过看采购期望（`PurchaseOrderRepository.deliveryStats`）

## 退货给供应商
- **能退什么**：采购来的批次，任何状态（待检、放行、不合格、冻结）。期初导入和生产的批次不能退（`SupplierReturnService.create`）
- **谁发起与签批**：管理员、采购、仓库发起；QA 或 QSO 签批（不能是发起人，要输入密码），**签批时才扣库存**（`SupplierReturnService.approve`）
- **退货物流与供应商门户签收确认（V28 新增）**：
  - 质量签批后，采购或仓库可登记承运商 `carrier` 与运单号 `trackingNo`（`POST /api/supplier-returns/{id}/ship`）
  - 供应商在门户查看退货明细与物流单号，点击“确认收到退货”并填写签收备注（`POST /api/portal/supplier/returns/{id}/confirm`）
- **对账联动**：结算前退的自动扣减结算数量；结算后退的在下一张对账单生成负数冲减行（`SettlementService.unbilled`）
