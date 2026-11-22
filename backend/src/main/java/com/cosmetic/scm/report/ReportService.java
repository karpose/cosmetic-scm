package com.cosmetic.scm.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.common.XlsxWriter;
import com.cosmetic.scm.complaint.ComplaintService;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.mes.EquipmentService;
import com.cosmetic.scm.production.ProductionService;
import com.cosmetic.scm.purchase.PurchaseService;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.qms.RetainService;
import com.cosmetic.scm.qms.SupplierAuditService;
import com.cosmetic.scm.sales.SalesService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报表导出。导出的是全部数据，不是页面上的最近几百条；超过上限直接报错，绝不悄悄截断。
 * 每次导出写审计日志（数据离开系统要留痕）。
 * 只读账号也能导出：能看到的数据才能导出，门户账号碰不到 /api/reports。
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    /** 单次导出的行数上限：再多生成的文件 Excel 也很难用，审计日志可以按日期范围分批导。 */
    static final int MAX_ROWS = 200_000;

    private final InventoryService inventory;
    private final MasterService master;
    private final ProductionService production;
    private final SalesService sales;
    private final ComplaintService complaints;
    private final DeviationService deviations;
    private final com.cosmetic.scm.plm.StabilityService stability;
    private final com.cosmetic.scm.plm.PlmInsightsService plmInsights;
    private final com.cosmetic.scm.plm.DocControlService docControl;
    private final com.cosmetic.scm.analytics.QualityGovernanceService qualityGov;
    private final com.cosmetic.scm.analytics.QualityEventAnalyticsService qualityEvents;
    private final com.cosmetic.scm.serial.SerialService serials;
    private final com.cosmetic.scm.integration.IntegrationService integration;
    private final com.cosmetic.scm.golive.GoLiveService golive;
    private final com.cosmetic.scm.analytics.ExpiryRiskAndReturnsService expiryReturns;
    private final com.cosmetic.scm.salesreturn.ReturnService salesReturns;
    private final com.cosmetic.scm.plm.ChangeService changeControl;
    private final com.cosmetic.scm.plm.ArtworkService artworks;
    private final com.cosmetic.scm.plm.ComplianceService compliance;
    private final com.cosmetic.scm.master.RegulatoryService regulatory;
    private final com.cosmetic.scm.quality.QualityService quality;
    private final com.cosmetic.scm.dealer.DealerService dealers;
    private final com.cosmetic.scm.analytics.ProductionEfficiencyService prodEfficiency;
    private final com.cosmetic.scm.inventory.StockOpsService stockOps;
    private final com.cosmetic.scm.security.UserService userService;
    private final com.cosmetic.scm.quality.QualitySpecService qualitySpecs;
    private final com.cosmetic.scm.planning.PlanningService planning;
    private final com.cosmetic.scm.sourcing.RfqService rfqService;
    private final com.cosmetic.scm.notify.NotificationLogRepository notificationLogs;
    private final com.cosmetic.scm.dashboard.DashboardService dashboardService;
    private final com.cosmetic.scm.plm.NpdService npdService;
    private final com.cosmetic.scm.plm.FilingService filingService;
    private final SupplierAuditService audits;
    private final RetainService retains;
    private final PurchaseService purchases;
    private final com.cosmetic.scm.settlement.SettlementService settlement;
    private final com.cosmetic.scm.settlement.ArSettlementService arSettlement;
    private final com.cosmetic.scm.settlement.AgingAnalysisService agingAnalysis;
    private final com.cosmetic.scm.sourcing.SupplierPriceService supplierPrices;
    private final com.cosmetic.scm.sales.CustomerPriceService customerPrices;
    private final com.cosmetic.scm.analytics.SupplierPerformanceService supplierPerformance;
    private final com.cosmetic.scm.analytics.BatchCostingService batchCosting;
    private final com.cosmetic.scm.analytics.FinanceInsightsService financeInsights;
    private final com.cosmetic.scm.supplierreturn.SupplierReturnService supplierReturns;
    private final EquipmentService equipment;
    private final AuditService audit;
    private final TimeProvider time;

    public record Export(String fileName, byte[] content) {
    }

    private record Table(String title, List<String> headers, List<List<Object>> rows) {
    }

    private Map<String, Supplier<Table>> registry(LocalDate from, LocalDate to, String q, String action) {
        Map<String, Supplier<Table>> m = new LinkedHashMap<>();
        m.put("stock", this::stock);
        m.put("batches", this::batches);
        m.put("purchases", this::purchases);
        m.put("purchase-expedite", this::purchaseExpediteReport);
        m.put("production", this::production);
        m.put("shipments", this::shipments);
        m.put("complaints", this::complaints);
        m.put("deviations", this::deviations);
        m.put("capas", this::capas);
        m.put("supplier-audits", this::supplierAudits);
        m.put("partner-compliance", this::partnerComplianceReport);
        m.put("retains", this::retainSamples);
        m.put("equipment", this::equipmentList);
        m.put("audit", () -> auditLogs(from, to, q, action));
        m.put("statements", this::statements);
        m.put("ar-statements", this::arStatements);
        m.put("ar-invoice-ledger", () -> arInvoiceLedger(from, to));
        m.put("customer-credit", this::customerCreditSummary);
        m.put("ar-collection", this::arCollectionReport);
        m.put("ap-aging", () -> agingTable(agingAnalysis.apAging(), "应付账龄分析"));
        m.put("ar-aging", () -> agingTable(agingAnalysis.arAging(), "应收账龄分析"));
        m.put("invoice-ledger", () -> invoiceLedger(from, to));
        m.put("supplier-prices", this::supplierPriceList);
        m.put("customer-prices", this::customerPriceList);
        m.put("batch-costs", this::batchCosts);
        m.put("margins", () -> marginReport(from, to));
        m.put("cashflow-forecast", this::cashFlowReport);
        m.put("inventory-variance", () -> inventoryVarianceReport(from, to));
        m.put("supplier-performance", () -> supplierPerformanceReport(from, to));
        m.put("supplier-returns", this::supplierReturns);
        m.put("stability", this::stabilityResults);
        m.put("plm-readiness", this::plmReadiness);
        m.put("where-used", this::whereUsedReport);
        m.put("npd-readiness", this::npdReadinessReport);
        m.put("pqr", () -> pqrReport(from, to));
        m.put("controlled-docs", this::controlledDocsReport);
        m.put("doc-compliance", this::docComplianceReport);
        m.put("qms-summary", this::qmsSummaryReport);
        m.put("qms-closure", this::qmsClosureReport);
        m.put("inventory-abc", this::inventoryAbcReport);
        m.put("serial-anomalies", this::serialAnomaliesReport);
        m.put("quality-events-summary", () -> qualityEventsSummaryReport(from, to));
        m.put("quality-event-closure", this::qualityEventClosureReport);
        m.put("integration-logs", () -> integrationLogsReport(from, to));
        m.put("golive-checklist", this::goliveChecklistReport);
        m.put("expiry-risk", this::expiryRiskReport);
        m.put("sales-returns", this::salesReturnsReport);
        m.put("changes", this::changesReport);
        m.put("artworks", this::artworksReport);
        m.put("compliance-status", this::complianceStatusReport);
        m.put("regulatory-violations", this::regulatoryViolationsReport);
        m.put("stability-coverage", this::stabilityCoverageReport);
        m.put("release-decisions", this::releaseDecisionsReport);
        m.put("ncr-disposition", this::ncrDispositionReport);
        m.put("dealer-orders", this::dealerOrdersReport);
        m.put("production-efficiency", this::productionEfficiencyReport);
        m.put("oem-consignment", this::oemConsignmentReport);
        m.put("material-variance", this::materialVarianceReport);
        m.put("quality-specs", this::qualitySpecsReport);
        m.put("inventory-ops", this::inventoryOpsReport);
        m.put("users", this::usersReport);
        m.put("items", this::itemsReport);
        m.put("partners", this::partnersReport);
        m.put("planning-fg", this::planningFgReport);
        m.put("planning-kitting", this::planningKittingReport);
        m.put("rfqs", this::rfqsReport);
        m.put("notification-logs", this::notificationLogsReport);
        m.put("todos", this::todosReport);
        m.put("npd-projects", this::npdProjectsReport);
        m.put("filings", this::filingsReport);
        m.put("inspections", () -> inspectionResults(from, to));
        return m;
    }

    @Transactional
    public Export export(String key, LocalDate from, LocalDate to) {
        return export(key, from, to, null, null);
    }

    @Transactional
    public Export export(String key, LocalDate from, LocalDate to, String q, String action) {
        // 金额只给采购和财务看，应收报表给销售、财务和管理员看，上线检查仅限管理员/主数据/质量导出，其它报表所有内部岗位都能导
        if (("statements".equals(key) || "ap-aging".equals(key) || "invoice-ledger".equals(key) || "supplier-prices".equals(key) || "rfqs".equals(key) || "batch-costs".equals(key) || "margins".equals(key) || "cashflow-forecast".equals(key) || "inventory-variance".equals(key) || "inventory-abc".equals(key)) && !com.cosmetic.scm.security.PriceAccess.allowed()) {
            throw new BizException(org.springframework.http.HttpStatus.FORBIDDEN, "没有权限导出财务对账与成本数据");
        }
        if (("ar-statements".equals(key) || "ar-aging".equals(key) || "ar-invoice-ledger".equals(key) || "customer-credit".equals(key) || "ar-collection".equals(key) || "customer-prices".equals(key)) && !arReportAllowed()) {
            throw new BizException(org.springframework.http.HttpStatus.FORBIDDEN, "没有权限导出客户应收对账数据");
        }
        if ("golive-checklist".equals(key) && !goliveReportAllowed()) {
            throw new BizException(org.springframework.http.HttpStatus.FORBIDDEN, "没有权限导出上线检查报告");
        }
        if (("users".equals(key) || "notification-logs".equals(key)) && !adminReportAllowed()) {
            throw new BizException(org.springframework.http.HttpStatus.FORBIDDEN, "仅系统管理员可导出该管理台账");
        }
        Supplier<Table> source = registry(from, to, q, action).get(key);
        if (source == null) {
            throw BizException.notFound("报表");
        }
        Table t = source.get();
        if (t.rows().size() > MAX_ROWS) {
            throw new BizException(t.title() + "超过 " + MAX_ROWS + " 行，请缩小日期范围后分批导出");
        }
        audit.log("导出报表", t.title(), t.rows().size() + " 行" + (from == null && to == null ? "" : "，范围 " + (from == null ? "不限" : from) + " 至 " + (to == null ? "不限" : to)));
        return new Export(t.title() + "-" + time.today() + ".xlsx", XlsxWriter.write(t.title(), t.headers(), t.rows()));
    }

    private static boolean goliveReportAllowed() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_MASTER_DATA".equals(a.getAuthority())
                        || "ROLE_QA".equals(a.getAuthority()) || "ROLE_QSO".equals(a.getAuthority()));
    }

    private static boolean adminReportAllowed() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private Table supplierReturns() {
        List<List<Object>> rows = new ArrayList<>();
        supplierReturns.listAll().forEach(r -> rows.add(row(r.returnNo(), r.supplierName(), r.batchNo(), r.extBatchNo(), r.itemName(), r.qty(), r.unit(), r.warehouseName(),
                r.reason(), r.statusLabel(), r.createdBy(), r.signedBy(), r.signedAt(), r.carrier(), r.trackingNo(), r.shippedBy(), r.shippedAt(),
                r.supplierConfirmedBy(), r.supplierConfirmedAt(), r.supplierNote(),
                r.receiptSource() == null ? null : switch (r.receiptSource()) {
                    case PORTAL -> "供应商门户";
                    case OFFLINE -> "采购代录";
                }, r.receivedOn(), r.receiptReference())));
        return new Table("退供记录", List.of("退供单", "供应商", "批号", "供应商批号", "物料", "数量", "单位", "仓库", "原因", "状态", "发起人", "签批人", "签批时间",
                "承运商", "运单号", "物流登记人", "物流登记时间", "签收登记人", "签收登记时间", "签收备注", "签收来源", "实际签收日期", "回单或凭证编号"), rows);
    }

    private static boolean arReportAllowed() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SALES".equals(a.getAuthority()) || "ROLE_FINANCE".equals(a.getAuthority()));
    }

    private Table statements() {
        List<List<Object>> rows = new ArrayList<>();
        settlement.listAll().forEach(t -> rows.add(row(t.statementNo(), t.supplierName(), t.periodTo(), t.statusLabel(), t.goodsAmount(), t.taxAmount(), t.adjustment(), t.adjustmentNote(),
                t.totalAmount(), t.invoiceNo(), t.invoiceDate(), t.paidAmount(), t.unpaid(), t.createdBy(), t.approvedBy(),
                t.creditDays(), t.dueDate(), t.overdueDays(), t.totalAmount().signum() < 0 ? "供应商退款" : "付款")));
        return new Table("对账单", List.of("对账单", "供应商", "对账截止", "状态", "含税货款", "其中税额", "调整", "调整原因", "合计", "发票号", "发票日期",
                "已结（退款为负）", "未结（退款为负）", "制单", "审批", "账期（天）", "到期日", "逾期天数", "收付方向"), rows);
    }

    private Table arStatements() {
        List<List<Object>> rows = new ArrayList<>();
        arSettlement.listAll().forEach(t -> rows.add(row(t.statementNo(), t.customerCode(), t.customerName(), t.customerTaxId(),
                t.periodTo(), t.creditDays(), t.dueDate(), t.overdueDays(), t.statusLabel(),
                t.goodsAmount(), t.taxAmount(), t.adjustment(), t.totalAmount(), t.receivedAmount(), t.unreceived(),
                t.invoiceNo(), t.invoiceDate(), t.extVoucherNo(), t.createdBy(), t.approvedBy())));
        return new Table("客户应收对账单", List.of("应收单号", "客户编码", "客户名称", "客户税号",
                "对账截止", "账期（天）", "到期日", "逾期天数", "状态",
                "含税货款", "销项税额", "调整金额", "应收合计", "已回款", "待回款",
                "销项发票号", "开票日期", "外部凭证号", "制单", "审批"), rows);
    }

    private Table agingTable(com.cosmetic.scm.settlement.AgingAnalysisService.AgingReport rep, String title) {
        List<List<Object>> rows = new ArrayList<>();
        rep.rows().forEach(r -> rows.add(row(r.partnerCode(), r.partnerName(), r.openCount(),
                r.notDue(), r.days1to30(), r.days31to60(), r.days61to90(), r.over90(), r.total())));
        rows.add(row("合计", "统计日 " + rep.asOf(), rep.rows().stream().mapToInt(r -> r.openCount()).sum(),
                rep.notDue(), rep.days1to30(), rep.days31to60(), rep.days61to90(), rep.over90(), rep.total()));
        return new Table(title, List.of("往来单位编码", "往来单位名称", "未结单数",
                "未到期", "逾期 1-30 天", "逾期 31-60 天", "逾期 61-90 天", "逾期 90 天以上", "未结合计"), rows);
    }

    private Table arInvoiceLedger(LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        arSettlement.invoiceLedger(from, to, null).rows().forEach(r -> rows.add(row(
                r.invoiceMonth(), r.invoiceDate(), r.invoiceNo(), r.customerCode(), r.customerName(),
                r.customerTaxId(), r.financeCode(), r.statementNo(), r.statementStatusLabel(),
                r.netAmount(), r.taxAmount(), r.amount(), r.approvedBy())));
        return new Table("销项发票台账", List.of("开票月份", "开票日期", "销项发票号", "客户编码", "客户名称",
                "客户税号", "财务客商编码", "关联应收单", "状态", "不含税金额", "销项税额", "含税合计", "审批人"), rows);
    }

    private Table customerCreditSummary() {
        List<List<Object>> rows = new ArrayList<>();
        arSettlement.creditSummary().forEach(r -> rows.add(row(
                r.customerCode(), r.customerName(), r.channel(), r.defaultCreditDays(),
                r.creditLimit(), r.openCount(), r.openAr(), r.overdueAr(), r.availableCredit(),
                r.utilizationPct(), r.overLimit() ? "超额" : "正常")));
        return new Table("客户授信使用概览", List.of("客户编码", "客户名称", "渠道", "默认账期（天）",
                "授信额度", "未结单数", "未结应收", "逾期应收", "剩余可用额度", "使用率（%）", "状态"), rows);
    }

    private Table arCollectionReport() {
        List<List<Object>> rows = new ArrayList<>();
        arSettlement.collectionBoard().rows().forEach(r -> rows.add(row(
                r.riskLabel(), r.statementNo(), r.customerCode(), r.customerName(), r.channel(), r.contact(),
                r.periodTo(), r.dueDate(), r.overdueDays(), r.totalAmount(), r.receivedAmount(), r.unreceived(),
                r.creditLimit(), r.customerOpenAr(), r.overLimit() ? "超额" : "正常", r.collectionAction())));
        return new Table("客户应收催收与信用风控清单", List.of("风险分级", "应收单号", "客户编码", "客户名称", "渠道", "联系方式",
                "对账截止", "到期日", "逾期天数", "应收合计", "已回款", "待回款",
                "授信额度", "客户未结应收", "授信状态", "催收建议"), rows);
    }

    private Table batchCosts() {
        List<List<Object>> rows = new ArrayList<>();
        batchCosting.report().forEach(r -> rows.add(row(
                r.moNo(), r.moTypeLabel(), r.factoryName(), r.fgCode(), r.fgName(), r.outBatchNo(),
                r.actualQty(), r.yieldPct(), r.materialCost(), r.processingCost(), r.totalCost(),
                r.actualUnitCost(), r.stdUnitCost(), r.variancePct(), r.varianceAmount(),
                r.anomalyLabel(), r.costDriver(), r.completedAt())));
        return new Table("生产批次实际成本", List.of("生产单号", "类型", "生产方", "成品编码", "成品名称", "产出批号",
                "实际产量", "收率（%）", "领料成本", "委外加工费", "总成本", "实际单位成本", "标准单位成本",
                "偏差（%）", "偏差金额（元）", "异常分级", "主要归因", "完工时间"), rows);
    }

    private Table marginReport(LocalDate from, LocalDate to) {
        var rep = financeInsights.margins(from, to);
        List<List<Object>> rows = new ArrayList<>();
        rep.byItem().forEach(r -> rows.add(row("按成品", r.code(), r.name(), r.qty(), r.revenue(), r.cogs(), r.grossProfit(), r.grossMarginPct())));
        rep.byCustomer().forEach(r -> rows.add(row("按客户", r.code(), r.name(), r.qty(), r.revenue(), r.cogs(), r.grossProfit(), r.grossMarginPct())));
        rows.add(row("汇总", rep.from() + "~" + rep.to(), "全部", null, rep.totalRevenue(), rep.totalCogs(), rep.totalGrossProfit(), rep.grossMarginPct()));
        return new Table("毛利分析", List.of("维度", "编码", "名称", "出库数量", "含税销售额", "销售成本", "毛利额", "毛利率（%）"), rows);
    }

    private Table cashFlowReport() {
        var rep = financeInsights.cashFlowForecast();
        List<List<Object>> rows = new ArrayList<>();
        rep.buckets().forEach(b -> rows.add(row(b.label(), b.inflow(), b.outflow(), b.netCashFlow(), b.arCount(), b.apCount())));
        rows.add(row("合计（" + rep.asOf() + "）", rep.totalInflow(), rep.totalOutflow(), rep.netCashFlow(), null, null));
        return new Table("现金流预测", List.of("时间区间", "预计回款/流入", "预计付款/流出", "预计净现金流", "涉及应收单", "涉及应付单"), rows);
    }

    private Table inventoryVarianceReport(LocalDate from, LocalDate to) {
        var rep = financeInsights.inventoryVariance(from, to);
        List<List<Object>> rows = new ArrayList<>();
        rep.rows().forEach(r -> rows.add(row(r.docNo(), r.docTypeLabel(), r.createdAt(), r.itemCode(), r.itemName(), r.unit(), r.qty(), r.unitCost(), r.amount(), r.reason())));
        rows.add(row("汇总", "盘盈/盘亏/报废", null, rep.from() + "~" + rep.to(), "盘盈 " + rep.gainValue() + " / 盘亏 " + rep.lossValue() + " / 报废 " + rep.scrapValue(), null, null, null, rep.netValue(), null));
        return new Table("库存盘点报废损益", List.of("作业单号", "类型", "时间", "物料编码", "物料名称", "单位", "差异数量", "单位成本", "损益金额", "原因"), rows);
    }

    private Table plmReadiness() {
        List<List<Object>> rows = new ArrayList<>();
        plmInsights.productReadiness().forEach(r -> rows.add(row(
                r.fgCode(), r.fgName(), r.effectiveVersionNo() == null ? null : "V" + r.effectiveVersionNo(),
                r.filingNo(), r.safetyState(), r.labelState(), r.stabilityState(), r.supportedMonths(),
                r.hasQualitySpec() ? "已生效" : "缺失", r.readyForRelease() ? "就绪" : "未就绪")));
        return new Table("产品合规就绪度矩阵", List.of("成品编码", "成品名称", "生效配方", "备案/注册号",
                "安全评估", "标签审核", "稳定性", "支持保质期(月)", "质量标准", "上市放行就绪"), rows);
    }

    private Table whereUsedReport() {
        List<List<Object>> rows = new ArrayList<>();
        plmInsights.whereUsedAllEffective().forEach(r -> rows.add(row(
                r.itemCode(), r.itemName(), r.fgCode(), r.fgName(), r.filingNo(),
                "V" + r.versionNo(), r.statusLabel(), r.qtyPer(), r.unit(), r.concentrationPct(),
                r.supplyModeLabel(), r.checkMessage())));
        return new Table("原料包材反查清单", List.of("物料编码", "物料名称", "成品编码", "成品名称", "备案号",
                "配方版本", "版本状态", "每件单耗", "单位", "浓度(%)", "供料方式", "合规校验"), rows);
    }

    private Table npdReadinessReport() {
        List<List<Object>> rows = new ArrayList<>();
        plmInsights.npdReadiness().forEach(r -> rows.add(row(
                r.projectNo(), r.name(), r.category(), r.filingTypeLabel(), r.statusLabel(),
                r.targetLaunch(), r.daysLeft(), r.fgCode(), r.fgName(),
                r.passedCount() + "/" + r.totalCount(), r.readinessPct(),
                r.ready() ? "就绪" : String.join("、", r.missingItems()))));
        return new Table("新品上市就绪度", List.of("项目编号", "产品名称", "品类", "备案类型", "状态",
                "计划上市日", "剩余天数", "成品编码", "成品名称", "通过项", "完成率(%)", "待补齐门禁"), rows);
    }

    private Table pqrReport(LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        qualityGov.pqrAll(from, to).forEach(r -> rows.add(row(
                r.fgCode(), r.fgName(), r.filingNo(), r.from() + "~" + r.to(),
                r.batchCount(), r.totalActualQty(), r.avgYieldPct(), r.fgPassRatePct(),
                r.deviationCount(), r.majorOrCriticalDeviations(), r.complaintCount(), r.adverseCount(),
                r.recallCount(), r.returnedQty(), r.effectiveVersionNo() == null ? null : "V" + r.effectiveVersionNo(),
                r.formulaChangesInPeriod(), r.stabilitySupportedMonths())));
        return new Table("产品年度质量回顾", List.of("成品编码", "成品名称", "备案号", "统计区间",
                "完工批数", "总产量", "平均收率(%)", "放行合格率(%)",
                "偏差数", "重大/严重偏差", "投诉数", "不良反应", "召回数", "退货数量",
                "现行配方", "期内变更次数", "稳定性支持月数"), rows);
    }

    private Table controlledDocsReport() {
        List<List<Object>> rows = new ArrayList<>();
        docControl.list().forEach(d -> rows.add(row(
                d.docCode(), d.title(), d.categoryLabel(), d.ownerDept(), d.statusLabel(),
                d.currentRev() == null ? null : "V" + d.currentRev(), d.currentEffective(),
                d.reviewDue(), d.reviewOverdue() ? "已超期" : "正常",
                d.ackPending(), d.copiesOut(), d.copiesToRetrieve())));
        return new Table("受控文件台账", List.of("文件编号", "名称", "类别", "归口部门", "状态",
                "现行版本", "生效日期", "下次复审", "复审状态", "待确认人数", "在用纸质副本", "待收回副本"), rows);
    }

    private Table docComplianceReport() {
        List<List<Object>> rows = new ArrayList<>();
        docControl.complianceBoard().rows().forEach(r -> rows.add(row(
                r.riskLabel(), r.issueTypeLabel(), r.docCode(), r.title(), r.categoryLabel(), r.ownerDept(),
                r.currentRev() == null ? null : "V" + r.currentRev(), r.dueDate(), r.daysLeft(),
                r.detail(), r.recommendedAction())));
        return new Table("受控文件合规闭环监控清单", List.of("风险分级", "合规事项", "文件编号", "文件名称", "类别", "归口部门",
                "现行版本", "截止日期", "剩余天数", "具体情况", "处置建议"), rows);
    }

    private Table qmsSummaryReport() {
        var s = qualityGov.qmsSummary();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("偏差管理", s.totalDeviations(), s.openDeviations(), s.overdueDeviations(), s.closedDeviations(), s.deviationCloseRatePct()));
        rows.add(row("CAPA 闭环", s.totalCapas(), s.openCapas(), s.overdueCapas(), s.verifiedCapas(), s.capaVerifyRatePct()));
        rows.add(row("供应商审核", s.auditPartnersTotal(), s.auditDueCount(), s.auditOverdueCount(), s.qualifiedPartners(), null));
        rows.add(row("设备校准", s.equipmentTotal(), s.equipmentCalDue(), s.equipmentCalOverdue(), s.equipmentInUse(), null));
        return new Table("质量体系健康度", List.of("维度", "总数", "进行中/将到期", "已超期", "已闭环/在用", "闭环率(%)"), rows);
    }

    private Table qmsClosureReport() {
        List<List<Object>> rows = new ArrayList<>();
        deviations.closureBoard().rows().forEach(r -> rows.add(row(
                r.riskLabel(), r.itemTypeLabel(), r.refNo(), r.title(), r.levelOrStatus(),
                r.ownerOrArea(), r.batchNo(), r.dueDate(), r.daysLeft(), r.escalationAction())));
        return new Table("偏差与CAPA闭环升级监控", List.of("风险分级", "事项类型", "编号", "标题/措施", "等级/状态",
                "环节/负责人", "关联批号/来源", "期限", "剩余天数", "升级处置建议"), rows);
    }

    private Table inventoryAbcReport() {
        var rep = qualityGov.inventoryAbc();
        List<List<Object>> rows = new ArrayList<>();
        rep.rows().forEach(r -> rows.add(row(
                r.abcClass(), r.itemCode(), r.itemName(), r.itemType(), r.unit(),
                r.qty(), r.unitCost(), r.value(), r.sharePct(), r.cumulativePct())));
        return new Table("库存ABC价值分类", List.of("ABC分类", "物料编码", "物料名称", "类型", "单位",
                "在库数量", "单位成本", "库存金额", "金额占比(%)", "累计占比(%)"), rows);
    }

    private Table invoiceLedger(LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        settlement.invoiceLedger(from, to, null, null).rows().forEach(r -> rows.add(row(
                r.invoiceMonth(), r.invoiceDate(), r.invoiceNo(), r.invoiceTypeLabel(),
                r.supplierName(), r.supplierTaxId(), r.financeCode(), r.statementNo(), r.statementStatusLabel(),
                r.taxRate(), r.netAmount(), r.taxAmount(), r.amount(), r.note(), r.createdBy())));
        return new Table("进项发票台账", List.of("开票月份", "开票日期", "发票号", "票种",
                "供应商", "销方税号", "财务客商编码", "关联对账单", "对账单状态",
                "税率（%）", "不含税金额", "税额", "含税金额", "备注", "登记人"), rows);
    }

    private Table supplierPriceList() {
        List<List<Object>> rows = new ArrayList<>();
        supplierPrices.list(null, null, false).forEach(p -> rows.add(row(
                p.supplierCode(), p.supplierName(), p.itemCode(), p.itemName(), p.unit(),
                p.unitPrice(), p.taxRate(), p.validFrom(), p.validTo(),
                !p.active() ? "已停用" : p.effectiveToday() ? "生效中" : "未生效/过期", p.note(), p.updatedBy(), p.updatedAt())));
        return new Table("供应商协议价", List.of("供应商编码", "供应商", "物料编码", "物料名称", "单位",
                "含税协议价", "税率（%）", "生效日期", "失效日期", "状态", "备注", "维护人", "更新时间"), rows);
    }

    private Table customerPriceList() {
        List<List<Object>> rows = new ArrayList<>();
        customerPrices.list(null, null, false).forEach(p -> rows.add(row(
                p.customerCode(), p.customerName(), p.itemCode(), p.itemName(), p.unit(),
                p.unitPrice(), p.taxRate(), p.validFrom(), p.validTo(),
                !p.active() ? "已停用" : p.effectiveToday() ? "生效中" : "未生效/过期", p.note(), p.updatedBy(), p.updatedAt())));
        return new Table("客户协议价", List.of("客户编码", "客户名称", "成品编码", "成品名称", "单位",
                "含税供货价", "税率（%）", "生效日期", "失效日期", "状态", "备注", "维护人", "更新时间"), rows);
    }

    private Table supplierPerformanceReport(LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        supplierPerformance.report(from, to).forEach(r -> rows.add(row(
                r.supplierCode(), r.supplierName(), r.qualified() ? "合格" : "未批准",
                r.totalBatches(), r.releasedBatches(), r.rejectedBatches(), r.passRatePct(),
                r.deliveredPoCount(), r.onTimePoCount(), r.onTimeRatePct(),
                r.receiptDisputeCount(), r.returnCount(), r.activeContractCount(), r.score(), r.grade())));
        return new Table("供应商绩效考核", List.of("供应商编码", "供应商名称", "准入状态",
                "来料总批数", "放行批数", "不合格批数", "来料合格率（%）",
                "已交付PO数", "准时PO数", "准时交货率（%）",
                "收货异议次数", "退供次数", "有效协议价数", "综合得分", "考核等级"), rows);
    }

    private Table stock() {
        Map<Long, BigDecimal> costs = master.unitCosts();
        List<List<Object>> rows = new ArrayList<>();
        inventory.listStock(null, null, null, false).forEach(s -> {
            BigDecimal cost = costs.get(s.itemId());
            rows.add(row(s.batchNo(), s.itemCode(), s.itemName(), s.warehouseName(), s.qty(), s.unit(), s.statusLabel(),
                    s.mfgDate(), s.expDate(), cost, cost == null ? null : cost.multiply(s.qty()).setScale(2, java.math.RoundingMode.HALF_UP)));
        });
        return new Table("库存明细", List.of("批号", "物料编码", "物料名称", "仓库", "数量", "单位", "状态", "生产日期", "有效期", "单位成本（元）", "金额（元）"), rows);
    }

    private Table batches() {
        List<List<Object>> rows = new ArrayList<>();
        inventory.listAllBatches().forEach(b -> rows.add(row(b.batchNo(), b.itemCode(), b.itemName(), b.statusLabel(),
                b.mfgDate(), b.expDate(), b.qtyIn(), b.onHand(), b.unit(), b.partnerName(), b.extBatchNo(), b.sourceNo(), b.freezeReason())));
        return new Table("批次台账", List.of("批号", "物料编码", "物料名称", "状态", "生产日期", "有效期", "入库数量", "结存", "单位", "来源单位", "外部批号", "来源单号", "冻结原因"), rows);
    }

    private Table purchases() {
        List<List<Object>> rows = new ArrayList<>();
        purchases.listAll().forEach(p -> p.lines().forEach(l -> rows.add(row(p.poNo(), p.orderDate(), p.supplierName(), p.statusLabel(),
                l.itemCode(), l.itemName(), l.qty(), l.receivedQty(), l.unit(), p.expectedDate(), p.promisedDate(), p.lastReceivedOn(), p.closeReason()))));
        return new Table("采购明细", List.of("采购单", "日期", "供应商", "状态", "物料编码", "物料名称", "订购数量", "已收数量", "单位", "期望到货", "供应商交期", "最后收货", "取消 / 关闭原因"), rows);
    }

    private Table purchaseExpediteReport() {
        List<List<Object>> rows = new ArrayList<>();
        purchases.expediteBoard().forEach(r -> rows.add(row(
                r.riskLabel(), r.poNo(), r.supplierName(), r.supplierContact(), r.orderDate(),
                r.dueDate(), r.dueSource(), r.daysLeft(), r.itemCode(), r.itemName(),
                r.qty(), r.receivedQty(), r.remainingQty(), r.unit(),
                String.join("、", r.openShipmentNos()), r.expediteAction())));
        return new Table("采购在途催货清单", List.of("交付风险", "采购单号", "供应商", "联系方式", "下单日期",
                "交期", "交期来源", "剩余天数", "物料编码", "物料名称",
                "订购数", "已收数", "未收数", "单位", "在途送货通知", "催货建议"), rows);
    }

    private Table production() {
        List<List<Object>> rows = new ArrayList<>();
        production.listAll().forEach(m -> rows.add(row(m.moNo(), m.moTypeLabel(), m.factoryName(), m.fgCode(), m.fgName(), m.plannedQty(),
                m.plannedDate(), m.statusLabel(), m.outBatchNo(), m.actualQty(), m.yieldPct(), m.cancelReason())));
        return new Table("生产单", List.of("单号", "类型", "生产方", "成品编码", "成品名称", "计划数量", "计划日期", "状态", "产出批号", "实际产量", "收率（%）", "取消原因"), rows);
    }

    private Table shipments() {
        List<List<Object>> rows = new ArrayList<>();
        sales.listAll().forEach(s -> s.allocations().forEach(a -> rows.add(row(s.soNo(), s.orderDate(), s.customerName(), s.channel(),
                s.warehouseName(), a.itemName(), a.batchNo(), a.qty()))));
        return new Table("出库明细", List.of("出库单", "日期", "客户", "渠道", "发货仓", "产品", "批号", "数量"), rows);
    }

    private Table complaints() {
        List<List<Object>> rows = new ArrayList<>();
        complaints.listAll().forEach(c -> rows.add(row(c.complaintNo(), c.receivedDate(), c.channel(), c.customerName(), c.itemName(), c.batchNo(),
                c.categoryLabel(), c.severityLabel(), c.description(), c.statusLabel(), c.reportDeadline(), c.reportNo(), c.reportedDate(), c.conclusion(), c.capa())));
        return new Table("投诉记录", List.of("编号", "受理日期", "渠道", "客户", "产品", "批号", "类别", "严重程度", "内容", "状态", "上报期限", "上报编号", "上报日期", "调查结论", "纠正预防措施"), rows);
    }

    private Table deviations() {
        List<List<Object>> rows = new ArrayList<>();
        deviations.listAll().forEach(d -> rows.add(row(d.deviationNo(), d.occurredDate(), d.areaLabel(), d.levelLabel(), d.title(), d.batchNo(), d.moNo(),
                d.description(), d.immediateAction(), d.rootCause(), d.dueDate(), d.statusLabel(), d.conclusion(), d.closedBy())));
        return new Table("偏差记录", List.of("编号", "发生日期", "环节", "等级", "标题", "批号", "生产单", "描述", "应急处理", "根本原因", "调查期限", "状态", "结论", "关闭人"), rows);
    }

    private Table capas() {
        List<List<Object>> rows = new ArrayList<>();
        deviations.listAllCapas().forEach(c -> rows.add(row(c.capaNo(), c.sourceLabel(), c.sourceNo(), c.actionLabel(), c.description(), c.owner(), c.dueDate(),
                c.statusLabel(), c.doneNote(), c.doneBy(), c.verifyNote(), c.verifiedBy())));
        return new Table("CAPA 记录", List.of("编号", "来源", "来源单号", "类型", "措施", "负责人", "期限", "状态", "完成情况", "完成人", "验证结论", "验证人"), rows);
    }

    private Table supplierAudits() {
        List<List<Object>> rows = new ArrayList<>();
        audits.listAll().forEach(a -> rows.add(row(a.auditNo(), a.partnerName(), a.auditDate(), a.auditTypeLabel(), a.score(), a.resultLabel(), a.findings(),
                a.auditor(), a.nextAuditDate(), a.signedBy())));
        return new Table("供应商审核", List.of("编号", "单位", "审核日期", "类型", "得分", "结论", "发现的问题", "审核员", "下次审核", "签批人"), rows);
    }

    private Table partnerComplianceReport() {
        List<List<Object>> rows = new ArrayList<>();
        audits.partnerRows().forEach(p -> rows.add(row(
                p.complianceRiskLabel(), p.code(), p.name(), p.kindLabel(), p.qualified() ? "在名录" : "已移出",
                p.critical() ? "是" : "否", p.licenseNo(), p.licenseExpiry(),
                "EXPIRED".equals(p.licenseState()) ? "已过期" : "EXPIRING".equals(p.licenseState()) ? "60天内到期" : "OK".equals(p.licenseState()) ? "有效" : "未登记",
                p.licenseDaysLeft(), p.lastAuditDate(), p.lastResultLabel(), p.nextAuditDate(),
                "OVERDUE".equals(p.dueState()) ? "已超期" : "DUE".equals(p.dueState()) ? "30天内到期" : "NONE".equals(p.dueState()) ? "未审核" : "正常",
                p.dueDaysLeft(), p.rejected(), p.received(), p.grade(), p.score())));
        return new Table("往来单位资质与审核合规预警", List.of("合规风险", "编码", "单位名称", "类别", "合格名录",
                "关键供应商", "资质证照号", "证照有效期", "证照状态", "证照剩余天数", "最近审核", "最近结论",
                "下次审核", "审核状态", "审核剩余天数", "近12月拒收批", "近12月来料批", "综合等级", "综合得分"), rows);
    }

    private Table inspectionResults(LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        qualitySpecs.exportRows(from, to).forEach(r -> rows.add(row(r.inspectionNo(), r.batchNo(), r.itemCode(), r.itemName(), r.itemType(), r.supplierName(), r.extBatchNo(), r.spec(),
                r.inspectedDate(), r.inspector(), r.sampleNote(), r.coaNo(), r.testName(), r.method(), r.limits(), r.result(), r.source(), r.judgement(), r.conclusion(), r.status(),
                r.voidReason(), r.deviationNo(), r.note())));
        return new Table("批次检验结果", List.of("检验单号", "批号", "物料编码", "物料", "类型", "来源单位", "外部批号", "质量标准", "检验日期", "检验人", "取样情况", "供方报告编号",
                "检验项目", "方法", "限度或标准", "结果", "结果来源", "判定", "本次结论", "状态", "作废原因", "偏差", "备注"), rows);
    }

    private Table stabilityResults() {
        List<List<Object>> rows = new ArrayList<>();
        stability.exportRows().forEach(r -> rows.add(row(r.studyNo(), r.fgCode(), r.fgName(), r.versionNo() == null ? null : "V" + r.versionNo(), r.sample(), r.studyType(),
                r.condition(), r.startDate(), r.status(), r.supportedMonths(), r.pullLabel(), r.dueDate(), r.pullStatus(), r.doneDate(), r.testedBy(), r.testName(),
                r.limits(), r.result(), r.judgement(), r.deviationNo(), r.note())));
        return new Table("稳定性试验结果", List.of("试验编号", "成品编码", "成品", "配方版本", "样品", "类型", "条件", "开始日期", "试验状态", "支持保质期（月）",
                "时间点", "计划日期", "取样状态", "取样日期", "检测人", "检测项目", "限度或标准", "结果", "判定", "偏差", "备注"), rows);
    }

    private Table retainSamples() {
        List<List<Object>> rows = new ArrayList<>();
        retains.listAll().forEach(s -> rows.add(row(s.batchNo(), s.itemName(), s.qty(), s.remainingQty(), s.location(), s.retainUntil(), s.statusLabel())));
        return new Table("留样台账", List.of("批号", "产品", "留样数量", "剩余数量", "存放地点", "保存至", "状态"), rows);
    }

    private Table equipmentList() {
        List<List<Object>> rows = new ArrayList<>();
        equipment.list().forEach(e -> rows.add(row(e.code(), e.name(), e.location(), e.statusLabel(), e.needsCalibration() ? "是" : "否", e.cycleMonths(),
                e.nextDue(), e.calibrations().isEmpty() ? null : e.calibrations().get(0).calibratedDate(),
                e.calibrations().isEmpty() ? null : e.calibrations().get(0).resultLabel(), e.stopReason())));
        return new Table("设备台账", List.of("编号", "名称", "位置", "状态", "需要校准", "周期（月）", "校准到期", "最近校准", "结论", "停用原因"), rows);
    }

    private Table serialAnomaliesReport() {
        List<List<Object>> rows = new ArrayList<>();
        serials.anomalies(null).forEach(a -> rows.add(row(
                a.displayCode(), a.seq(), a.batchNo(), a.itemCode(), a.itemName(), a.batchStatusLabel(),
                a.scanCount(), a.firstScanAt(), a.lastScanAt())));
        return new Table("防伪异常扫码清单", List.of("防伪码", "序号", "批号", "成品编码", "成品名称", "批次状态", "累计扫码次数", "首次扫码时间", "最近扫码时间"), rows);
    }

    private Table qualityEventsSummaryReport(LocalDate from, LocalDate to) {
        var rep = qualityEvents.report(from, to);
        List<List<Object>> rows = new ArrayList<>();
        rep.byCategory().forEach(r -> rows.add(row("按投诉类别", r.label(), r.count(), r.adverseCount(), r.seriousCount(), r.openCount(), r.sharePct(), null, null)));
        rep.byChannel().forEach(r -> rows.add(row("按来源渠道", r.label(), r.count(), r.adverseCount(), r.seriousCount(), r.openCount(), r.sharePct(), null, null)));
        rep.byItem().forEach(r -> rows.add(row("按成品", r.label(), r.count(), r.adverseCount(), r.seriousCount(), r.openCount(), r.sharePct(), null, null)));
        rep.recalls().forEach(r -> rows.add(row("召回效率", r.recallNo() + " " + r.itemName() + "(" + r.batchNo() + ")",
                r.customerCount(), r.notifiedCount(), null, "召回中".equals(r.statusLabel()) ? 1 : 0, r.notifyRatePct(), r.recoveryRatePct(), r.statusLabel())));
        return new Table("客诉与召回效率分析", List.of("维度", "名称/单号", "总件数/客户数", "不良反应/已通知", "严重件数", "处理中", "占比/通知率(%)", "回收率(%)", "状态"), rows);
    }

    private Table qualityEventClosureReport() {
        List<List<Object>> rows = new ArrayList<>();
        qualityEvents.closureBoard().rows().forEach(r -> rows.add(row(
                r.riskLabel(), r.itemTypeLabel(), r.refNo(), r.itemName(), r.batchNo(),
                r.detail(), r.dueDate(), r.daysLeft(), r.progressPct(), r.recommendedAction())));
        return new Table("不良反应上报与召回闭环监控", List.of("风险分级", "事项类型", "单号", "成品名称", "批号",
                "执行进度明细", "上报截止日", "剩余天数", "回收/完成率(%)", "处置建议"), rows);
    }

    private Table integrationLogsReport(LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        integration.exportLogs(from, to).forEach(l -> rows.add(row(
                l.createdAt(), "IN".equals(l.direction()) ? "平台→本系统" : "本系统→平台", l.platform(),
                l.action(), l.ref(), l.success() ? "成功" : "失败", l.message())));
        return new Table("系统对接日志", List.of("时间", "方向", "平台", "动作", "对象", "结果", "说明"), rows);
    }

    private Table goliveChecklistReport() {
        var cl = golive.checklist();
        List<List<Object>> rows = new ArrayList<>();
        cl.checks().forEach(c -> rows.add(row(
                c.group(), c.title(),
                "BLOCK".equals(c.state()) ? "必须处理" : "WARN".equals(c.state()) ? "建议处理" : "通过",
                c.detail())));
        return new Table("上线前检查报告", List.of("分组", "检查项", "状态", "说明"), rows);
    }

    private Table expiryRiskReport() {
        var rep = expiryReturns.expiryRisk();
        List<List<Object>> rows = new ArrayList<>();
        rep.riskRows().forEach(r -> rows.add(row(
                r.riskLabel(), r.batchNo(), r.itemCode(), r.itemName(), r.itemType(), r.warehouseName(),
                r.qty(), r.unit(), r.statusLabel(), r.mfgDate(), r.expDate(), r.daysLeft(),
                r.shelfRatio() == null ? null : Math.round(r.shelfRatio() * 1000.0) / 10.0,
                r.unitCost(), r.value(), r.recommendedAction())));
        return new Table("库存效期风险清单", List.of("风险等级", "批号", "物料编码", "物料名称", "类型", "仓库",
                "结存数量", "单位", "批次状态", "生产日期", "有效期至", "剩余天数", "剩余效期(%)", "单位成本（元）", "风险货值（元）", "建议处置动作"), rows);
    }

    private Table salesReturnsReport() {
        List<List<Object>> rows = new ArrayList<>();
        salesReturns.listAll().forEach(r -> r.lines().forEach(l -> rows.add(row(
                r.returnNo(), r.returnDate(), r.customerName(), r.warehouseName(), r.recallNo(),
                l.batchNo(), l.itemName(), l.qty(), l.unit(), l.dispositionLabel(),
                l.targetWarehouseName(), l.disposedByName(), l.disposedAt(), l.dispositionNote(),
                l.arStatementNo(), r.reason()))));
        return new Table("销售退货明细", List.of("退货单", "退货日期", "客户", "隔离仓", "关联召回",
                "批号", "成品", "数量", "单位", "处置结论", "转入仓", "签批人", "处置时间", "处置说明",
                "关联应收冲减单", "退货原因"), rows);
    }

    private Table changesReport() {
        List<List<Object>> rows = new ArrayList<>();
        changeControl.listAll().forEach(c -> rows.add(row(
                c.changeNo(), c.changeTypeLabel(), c.title(), c.scope(), c.riskLabel(), c.statusLabel(),
                c.actionsTotal(), c.actionsOpen(), c.actionsOverdue(), c.createdBy(), c.createdAt(),
                c.assessedBy(), c.approvedBy(), c.closedBy(), c.closedAt(), c.reason())));
        return new Table("变更控制台账", List.of("变更编号", "变更类型", "标题", "影响范围", "风险等级", "状态",
                "行动总数", "未完行动", "超期行动", "申请人", "申请时间", "评估人", "批准人", "关闭人", "关闭时间", "变更原因"), rows);
    }

    private Table artworksReport() {
        List<List<Object>> rows = new ArrayList<>();
        artworks.listAll().forEach(a -> rows.add(row(
                a.artworkNo(), a.versionNo(), a.pkCode(), a.pkName(), a.fgCode(), a.fgName(),
                a.formulaVersionNo() == null ? null : "V" + a.formulaVersionNo(), a.statusLabel(),
                a.stale() ? "是（需重评）" : "否", a.changeNo(), a.changeNote(),
                a.createdBy(), a.createdAt(), a.approvedBy(), a.approvedAt())));
        return new Table("标签设计稿台账", List.of("设计稿编号", "版本", "包材编码", "包材名称", "成品编码", "成品名称",
                "对应配方", "状态", "配方变更失效", "关联变更", "变更说明", "提交人", "提交时间", "批准人", "批准时间"), rows);
    }

    private Table complianceStatusReport() {
        List<List<Object>> rows = new ArrayList<>();
        compliance.statuses().forEach(p -> rows.add(row(
                p.fgCode(), p.fgName(), p.versionNo() == null ? null : "V" + p.versionNo(),
                p.safety(), p.efficacy(), p.label(), p.complete() ? "齐套" : "未齐套")));
        return new Table("产品合规齐套台账", List.of("成品编码", "成品名称", "生效配方", "安全评估", "功效宣称依据", "标签审核", "齐套状态"), rows);
    }

    private Table regulatoryViolationsReport() {
        var cmp = regulatory.compare();
        List<List<Object>> rows = new ArrayList<>();
        cmp.violations().forEach(v -> rows.add(row("配方冲突", v.fgCode(), v.fgName(), v.itemCode(), null, v.message())));
        cmp.gaps().forEach(g -> rows.add(row("物料差异", g.code(), g.name(), g.inci(), g.state(), g.detail())));
        return new Table("法规目录冲突与差异清单", List.of("类型", "编码", "名称", "关联物料/INCI", "差异分类", "问题描述"), rows);
    }

    private Table stabilityCoverageReport() {
        List<List<Object>> rows = new ArrayList<>();
        stability.coverage().forEach(p -> rows.add(row(
                p.fgCode(), p.fgName(), p.versionNo() == null ? null : "V" + p.versionNo(),
                p.shelfMonths(), p.supportedMonths(), p.state())));
        return new Table("成品保质期稳定性依据覆盖表", List.of("成品编码", "成品名称", "生效配方", "标的保质期(月)", "已签批支持(月)", "覆盖状态"), rows);
    }

    private Table releaseDecisionsReport() {
        List<List<Object>> rows = new ArrayList<>();
        quality.listAllDecisions().forEach(d -> rows.add(row(
                d.batchNo(), d.itemName(), d.stageLabel(), d.decisionLabel(), d.reportNo(),
                d.signedByName(), d.signedAt(), d.retainQty(), d.retainLocation(), d.retainUntil(), d.note())));
        return new Table("质量检验与上市放行签批台账", List.of("批号", "物料/成品", "放行环节", "签批结论", "检验报告号",
                "签批人", "签批时间", "留样数量", "留样地点", "留样保存至", "签批说明"), rows);
    }

    private Table ncrDispositionReport() {
        List<List<Object>> rows = new ArrayList<>();
        quality.ncrDispositions().forEach(r -> rows.add(row(
                r.batchNo(), r.itemCode(), r.itemName(), r.itemType(), r.batchStatusLabel(),
                r.partnerName(), r.sourceNo(), r.qtyIn(), r.onHand(), r.unit(), r.returnedQty(),
                String.join("、", r.supplierReturnNos()), r.openDeviationCount(), String.join("、", r.deviationNos()),
                r.closureStateLabel(), r.recommendedAction(), r.reason())));
        return new Table("不合格品与冻结批次处置闭环台账", List.of("批号", "编码", "物料/成品", "类型", "批次状态",
                "往来单位", "来源单号", "入库数量", "在库待处置", "单位", "已退供数量",
                "关联退供单", "未关闭偏差数", "关联偏差单", "闭环状态", "建议处置动作", "不合格/冻结原因"), rows);
    }

    private Table dealerOrdersReport() {
        List<List<Object>> rows = new ArrayList<>();
        dealers.listAll().forEach(o -> o.lines().forEach(l -> rows.add(row(
                o.orderNo(), o.createdAt(), o.customerName(), o.minShelfPct(), o.requestedDate(),
                o.statusLabel(), l.itemCode(), l.itemName(), l.qty(), l.unit(), o.soNo(),
                o.reviewedBy(), o.reviewedAt(), o.reviewNote(), o.note()))));
        return new Table("经销商订货履约明细", List.of("订货单号", "下单时间", "经销商", "效期要求(%)", "期望到货日",
                "状态", "成品编码", "成品名称", "订购数量", "单位", "生成出库单", "审核人", "审核时间", "审核说明", "备注"), rows);
    }

    private Table productionEfficiencyReport() {
        var rep = prodEfficiency.report();
        List<List<Object>> rows = new ArrayList<>();
        rep.byFactory().forEach(f -> rows.add(row("按生产方", f.factoryName(), f.moTypeLabel(),
                f.completedCount(), f.wipCount(), f.overdueCount(), f.totalPlannedQty(), f.totalActualQty(), f.avgYieldPct())));
        rep.byItem().forEach(i -> rows.add(row("按成品", i.fgName(), i.fgCode(),
                i.completedCount(), null, null, i.totalPlannedQty(), i.totalActualQty(), i.avgYieldPct())));
        return new Table("生产收率与工厂效率对比", List.of("维度", "名称", "类型/编码", "完工批数", "在制批数",
                "超期在制", "计划总产量", "实际总产量", "平均收率(%)"), rows);
    }

    private Table oemConsignmentReport() {
        List<List<Object>> rows = new ArrayList<>();
        production.oemConsignment().forEach(r -> rows.add(row(
                r.factoryName(), r.warehouseCode(), r.warehouseName(), r.batchNo(),
                r.itemCode(), r.itemName(), r.itemType(), r.qty(), r.unit(),
                r.statusLabel(), r.expDate(), r.unitCost(), r.value())));
        return new Table("委外寄售料账", List.of("受托厂", "寄存仓编码", "寄存仓名称", "批号",
                "物料编码", "物料名称", "类型", "在厂余料", "单位",
                "批次状态", "有效期至", "单位成本(元)", "余料货值(元)"), rows);
    }

    private Table materialVarianceReport() {
        List<List<Object>> rows = new ArrayList<>();
        production.materialVariance().forEach(r -> rows.add(row(
                r.moNo(), r.moTypeLabel(), r.factoryName(), r.fgCode(), r.fgName(), r.moStatusLabel(),
                r.plannedQty(), r.actualQty(), r.itemCode(), r.itemName(), r.unit(),
                r.standardQty(), r.netIssuedQty(), r.varianceQty(), r.variancePct(), r.stateLabel(),
                r.unitCost(), r.varianceCost())));
        return new Table("生产物料超耗与平衡台账", List.of("生产单号", "类型", "生产方", "成品编码", "成品名称", "工单状态",
                "计划产量", "实际产量", "物料编码", "物料名称", "单位",
                "理论标耗", "净发料量", "差异数量", "差异率(%)", "状态",
                "标准单价(元)", "差异金额(元)"), rows);
    }

    private Table qualitySpecsReport() {
        List<List<Object>> rows = new ArrayList<>();
        qualitySpecs.listAll().forEach(s -> rows.add(row(
                s.specNo(), "V" + s.versionNo(), s.itemCode(), s.itemName(), s.itemTypeLabel(),
                s.statusLabel(), s.lines().size(), s.lines().stream().filter(l -> l.coaAllowed()).count(),
                s.samplingPlan(), s.coaMaxStreak(), s.changeNote(), s.createdBy(), s.approvedBy(), s.approvedAt())));
        return new Table("质量标准台账", List.of("标准编号", "版本", "物料编码", "物料名称", "物料类型",
                "状态", "检验项数", "可采供方报告项", "取样要求", "连续采报告上限", "修改原因", "提交人", "签批人", "签批时间"), rows);
    }

    private Table inventoryOpsReport() {
        List<List<Object>> rows = new ArrayList<>();
        stockOps.listAll().forEach(d -> d.lines().forEach(l -> rows.add(row(
                d.docNo(), d.docTypeLabel(), d.createdAt(), d.fromWarehouseName(), d.toWarehouseName(),
                l.batchNo(), l.itemCode(), l.itemName(), l.bookQty(), l.qty(), l.unit(),
                d.createdBy(), d.signedByName(), d.reason()))));
        return new Table("库存作业明细", List.of("单号", "作业类型", "时间", "调出/作业仓", "调入仓",
                "批号", "物料编码", "物料名称", "账面数", "变动/数量", "单位", "经办人", "签批人", "原因"), rows);
    }

    private Table usersReport() {
        List<List<Object>> rows = new ArrayList<>();
        userService.list().forEach(u -> rows.add(row(
                u.username(), u.displayName(), u.enabled() ? "启用" : "停用",
                String.join("、", u.roles()), u.partnerName(), u.email(),
                u.lockedUntil(), u.passwordChangeRequired())));
        return new Table("账号与权限台账", List.of("账号", "姓名", "状态", "角色", "所属门户单位", "邮箱", "锁定截止", "密码状态"), rows);
    }

    private Table itemsReport() {
        boolean showPrice = com.cosmetic.scm.security.PriceAccess.allowed();
        List<List<Object>> rows = new ArrayList<>();
        master.listItems().forEach(i -> rows.add(row(
                i.getCode(), i.getName(), i.getItemType().getLabel(), i.getUnit(),
                i.isActive() ? "启用" : "停用", i.getInci(), i.getCasNo(), i.getLimitPct(),
                i.isBanned() ? "禁用" : "否", i.getShelfMonths(), i.getFilingNo(), i.getNetG(),
                showPrice ? i.getStdCost() : null, showPrice ? i.getListPrice() : null)));
        return new Table("物料主数据台账", List.of("编码", "名称", "类型", "单位", "状态", "INCI", "CAS号", "限用上限(%)",
                "禁用", "保质期(月)", "备案/注册号", "净含量(g)", "标准成本(元)", "指导价(元)"), rows);
    }

    private Table partnersReport() {
        boolean showCredit = arReportAllowed();
        List<List<Object>> rows = new ArrayList<>();
        master.listPartners().forEach(p -> rows.add(row(
                p.code(), p.name(), p.kindLabel(), p.qualified() ? "合格" : "未批准",
                p.criticalSupplier() ? "是" : "否", p.usable() ? "可下单" : "受限",
                p.licenseNo(), p.licenseExpiry(),
                "EXPIRED".equals(p.licenseState()) ? "已过期" : "EXPIRING".equals(p.licenseState()) ? "临期" : "OK".equals(p.licenseState()) ? "有效" : "未登记",
                p.licenseDaysLeft(), p.channel(), p.minShelfPct(), p.defaultCreditDays(),
                showCredit ? p.creditLimit() : null, p.contact(), p.address(), p.taxId(), p.financeCode())));
        return new Table("往来单位与资质台账", List.of("编码", "名称", "类别", "合格名录", "关键供应商", "下单状态",
                "资质编号", "资质有效期", "资质状态", "剩余天数", "渠道", "效期要求(%)", "默认账期(天)",
                "信用额度(元)", "联系人", "地址", "税号", "财务客商编码"), rows);
    }

    private Table planningFgReport() {
        boolean showPrice = com.cosmetic.scm.security.PriceAccess.allowed();
        List<List<Object>> rows = new ArrayList<>();
        planning.finishedGoods(new com.cosmetic.scm.planning.PlanningService.PlanParams(90, 30, 15, 60, false)).forEach(r -> rows.add(row(
                r.code(), r.name(), r.unit(), r.shipped(), r.dailyDemand(),
                r.available(), r.nearExpiry(), r.pendingRelease(), r.wip(), r.projected(),
                r.safetyStock(), r.reorderPoint(), r.daysOfSupply(),
                r.belowReorderPoint() ? "是" : "否", r.suggested(), r.factoryName(),
                showPrice ? r.unitCost() : null, showPrice ? r.listPrice() : null)));
        return new Table("成品补货建议", List.of("成品编码", "成品名称", "单位", "近90天出库", "日均需求",
                "可用库存", "其中临期", "待上市放行", "在制生产", "预计可用",
                "安全库存", "再订货点", "可售天数", "低于再订货点", "建议补产", "建议生产方",
                "单位成本(元)", "指导价(元)"), rows);
    }

    private Table planningKittingReport() {
        List<List<Object>> rows = new ArrayList<>();
        planning.kittingCheck().forEach(o -> {
            if (o.shortages().isEmpty()) {
                rows.add(row(o.moNo(), o.moTypeLabel(), o.factoryName(), o.fgCode(), o.fgName(),
                        o.plannedQty(), o.plannedDate(), o.kittingPct(), o.statusLabel(),
                        null, null, null, null, null, null, null, "已齐套，可直接发料"));
            } else {
                o.shortages().forEach(s -> rows.add(row(o.moNo(), o.moTypeLabel(), o.factoryName(), o.fgCode(), o.fgName(),
                        o.plannedQty(), o.plannedDate(), o.kittingPct(), o.statusLabel(),
                        s.itemCode(), s.itemName(), s.unit(), s.requiredQty(), s.allocatedQty(), s.shortQty(),
                        s.quarantineQty().add(s.onOrderQty()), s.supplyHint())));
            }
        });
        return new Table("生产排产齐套预检清单", List.of("生产单号", "类型", "生产方", "成品编码", "成品名称",
                "计划产量", "计划日期", "齐套率(%)", "齐套状态", "缺料编码", "缺料名称", "单位",
                "需求数量", "可分配数", "缺口数量", "待检+在途", "供应建议"), rows);
    }

    private Table rfqsReport() {
        List<List<Object>> rows = new ArrayList<>();
        rfqService.listAll().forEach(r -> {
            int invitedCount = r.suppliers().size();
            long quotedCount = r.suppliers().stream().filter(s -> s.quoted()).count();
            r.lines().forEach(l -> {
                var awarded = l.quotes().stream().filter(q -> q.awarded()).findFirst().orElse(null);
                rows.add(row(r.rfqNo(), r.title(), "第" + r.roundNo() + "轮", r.deadline(), r.statusLabel(),
                        invitedCount, quotedCount, l.itemCode(), l.itemName(), l.qty(), l.unit(),
                        l.lastPoPrice(), awarded == null ? null : awarded.supplierName(),
                        awarded == null ? null : awarded.unitPrice(), awarded == null ? null : awarded.poNo(),
                        r.awardedBy(), r.awardedAt()));
            });
        });
        return new Table("询价比价台账", List.of("询价单号", "标题", "轮次", "截止日期", "状态",
                "邀请家数", "已报价家数", "物料编码", "物料名称", "询价数量", "单位",
                "历史采购单价", "中标供应商", "中标含税单价", "生成采购单", "定标人", "定标时间"), rows);
    }

    private Table notificationLogsReport() {
        List<List<Object>> rows = new ArrayList<>();
        notificationLogs.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"))
                .forEach(l -> rows.add(row(l.getSentAt(), "MAIL".equals(l.getChannel()) ? "邮件" : "企业微信",
                        l.getRecipient(), l.getSubject(), l.getItemCount(), l.isSuccess() ? "成功" : "失败",
                        "schedule".equals(l.getTriggered()) ? "定时" : l.getTriggered(), l.getError())));
        return new Table("消息提醒发送记录", List.of("发送时间", "渠道", "收件人", "主题", "事项数", "结果", "触发方式", "失败原因"), rows);
    }

    private Table todosReport() {
        List<List<Object>> rows = new ArrayList<>();
        dashboardService.summary().todos().forEach(t -> rows.add(row(
                "bad".equals(t.level()) ? "紧急" : "warn".equals(t.level()) ? "警告" : "info".equals(t.level()) ? "常规" : "提示",
                t.label(), t.title(), t.ref(), t.detail(), t.route())));
        return new Table("当前待办清单", List.of("级别", "事项类别", "对象", "编号", "说明", "页面路径"), rows);
    }

    private Table npdProjectsReport() {
        List<List<Object>> rows = new ArrayList<>();
        npdService.listAll().forEach(p -> rows.add(row(
                p.projectNo(), p.name(), p.category(), p.filingTypeLabel(), p.statusLabel(),
                p.targetLaunch(), p.late() ? "已超期" : "正常", p.fgCode(), p.fgName(),
                p.trials(), p.pilotPassed() ? "通过" : "未通过", p.claims(),
                p.createdBy(), p.approvedBy(), p.launchedBy(), p.launchedAt())));
        return new Table("新品研发项目台账", List.of("项目编号", "产品名称", "品类", "备案类型", "状态",
                "计划上市日期", "进度状态", "关联成品编码", "关联成品名称", "试制次数", "中试通过", "宣称",
                "申请人", "立项批准人", "上市批准人", "上市批准时间"), rows);
    }

    private Table filingsReport() {
        List<List<Object>> rows = new ArrayList<>();
        filingService.listAll().forEach(f -> rows.add(row(
                f.fgCode(), f.fgName(), f.versionNo() == null ? null : "V" + f.versionNo(),
                f.filingTypeLabel(), f.filingNo(), f.statusLabel(),
                f.submittedDate(), f.filedDate(), f.expiryDate(), f.expiryDaysLeft(),
                f.createdBy(), f.note())));
        return new Table("产品备案与注册台账", List.of("成品编码", "成品名称", "对应配方", "类型", "备案/注册号", "状态",
                "提交日期", "完成日期", "有效期至", "剩余天数", "登记人", "备注"), rows);
    }

    private Table auditLogs(LocalDate from, LocalDate to, String q, String action) {
        List<List<Object>> rows = new ArrayList<>();
        // 多查一条，超限时能发现并报错，而不是悄悄少导；AuditService.search 内部已按 PriceAccess 遮盖涉价内容
        audit.search(from, to, q, MAX_ROWS + 1).stream()
                .filter(a -> action == null || action.isBlank() || action.trim().equals(a.getAction()))
                .forEach(a -> rows.add(row(a.getCreatedAt(), a.getUsername(), a.getDisplayName(), a.getAction(), a.getTarget(), a.getDetail(), a.getReason())));
        return new Table("审计日志", List.of("时间", "账号", "姓名", "操作", "对象", "内容", "原因"), rows);
    }

    private static List<Object> row(Object... cells) {
        List<Object> out = new ArrayList<>(cells.length);
        for (Object c : cells) {
            out.add(c);
        }
        return out;
    }
}
