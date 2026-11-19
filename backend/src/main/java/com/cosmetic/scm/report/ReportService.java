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
    private final com.cosmetic.scm.quality.QualitySpecService qualitySpecs;
    private final SupplierAuditService audits;
    private final RetainService retains;
    private final PurchaseService purchases;
    private final com.cosmetic.scm.settlement.SettlementService settlement;
    private final com.cosmetic.scm.settlement.ArSettlementService arSettlement;
    private final com.cosmetic.scm.sourcing.SupplierPriceService supplierPrices;
    private final com.cosmetic.scm.analytics.SupplierPerformanceService supplierPerformance;
    private final com.cosmetic.scm.supplierreturn.SupplierReturnService supplierReturns;
    private final EquipmentService equipment;
    private final AuditService audit;
    private final TimeProvider time;

    public record Export(String fileName, byte[] content) {
    }

    private record Table(String title, List<String> headers, List<List<Object>> rows) {
    }

    private Map<String, Supplier<Table>> registry(LocalDate from, LocalDate to) {
        Map<String, Supplier<Table>> m = new LinkedHashMap<>();
        m.put("stock", this::stock);
        m.put("batches", this::batches);
        m.put("purchases", this::purchases);
        m.put("production", this::production);
        m.put("shipments", this::shipments);
        m.put("complaints", this::complaints);
        m.put("deviations", this::deviations);
        m.put("capas", this::capas);
        m.put("supplier-audits", this::supplierAudits);
        m.put("retains", this::retainSamples);
        m.put("equipment", this::equipmentList);
        m.put("audit", () -> auditLogs(from, to));
        m.put("statements", this::statements);
        m.put("ar-statements", this::arStatements);
        m.put("invoice-ledger", () -> invoiceLedger(from, to));
        m.put("supplier-prices", this::supplierPriceList);
        m.put("supplier-performance", () -> supplierPerformanceReport(from, to));
        m.put("supplier-returns", this::supplierReturns);
        m.put("stability", this::stabilityResults);
        m.put("inspections", () -> inspectionResults(from, to));
        return m;
    }

    @Transactional
    public Export export(String key, LocalDate from, LocalDate to) {
        // 金额只给采购和财务看，应收报表给销售、财务和管理员看，其它报表所有内部岗位都能导
        if (("statements".equals(key) || "invoice-ledger".equals(key) || "supplier-prices".equals(key)) && !com.cosmetic.scm.security.PriceAccess.allowed()) {
            throw new BizException(org.springframework.http.HttpStatus.FORBIDDEN, "没有权限导出财务对账与发票数据");
        }
        if ("ar-statements".equals(key) && !arReportAllowed()) {
            throw new BizException(org.springframework.http.HttpStatus.FORBIDDEN, "没有权限导出客户应收对账数据");
        }
        Supplier<Table> source = registry(from, to).get(key);
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

    private Table auditLogs(LocalDate from, LocalDate to) {
        List<List<Object>> rows = new ArrayList<>();
        // 多查一条，超限时能发现并报错，而不是悄悄少导
        audit.search(from, to, null, MAX_ROWS + 1).forEach(a -> rows.add(row(a.getCreatedAt(), a.getUsername(), a.getDisplayName(), a.getAction(), a.getTarget(), a.getDetail(), a.getReason())));
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
