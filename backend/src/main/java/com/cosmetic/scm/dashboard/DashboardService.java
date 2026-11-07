package com.cosmetic.scm.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditLog;
import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.complaint.ComplaintService;
import com.cosmetic.scm.complaint.ComplaintService.ComplaintView;
import com.cosmetic.scm.dealer.DealerService;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.OemReportStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.integration.IntegrationService;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.InventoryService.BatchView;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.MasterDtos.PartnerView;
import com.cosmetic.scm.oemportal.OemReport;
import com.cosmetic.scm.oemportal.OemReportRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.mes.EquipmentService;
import com.cosmetic.scm.plm.FilingService;
import com.cosmetic.scm.plm.FormulaService;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.qms.RetainService;
import com.cosmetic.scm.qms.SupplierAuditService;
import com.cosmetic.scm.recall.RecallService;
import com.cosmetic.scm.recall.RecallService.RecallView;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.salesreturn.ReturnService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 总览：主线各环节数量 + 待办 + 最近操作。 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 成品剩余效期低于此比例时列为临期。 */
    private static final double NEAR_EXPIRY_RATIO = 0.5;

    private final PurchaseOrderRepository purchaseOrders;
    private final com.cosmetic.scm.purchase.PoShipmentRepository poShipments;
    private final com.cosmetic.scm.sourcing.RfqRepository rfqRepo;
    private final com.cosmetic.scm.settlement.SupplierStatementRepository statementRepo;
    private final BatchRepository batches;
    private final MfgOrderRepository mfgOrders;
    private final SalesOrderRepository salesOrders;
    private final InventoryService inventory;
    private final MasterService master;
    private final MasterLookup lookup;
    private final ComplaintService complaints;
    private final RecallService recalls;
    private final ReturnService returns;
    private final OemReportRepository oemReports;
    private final DealerService dealers;
    private final IntegrationService integration;
    private final DeviationService deviations;
    private final SupplierAuditService supplierAudits;
    private final RetainService retains;
    private final FormulaService formulas;
    private final FilingService filings;
    private final EquipmentService equipment;
    private final AuditService audit;
    private final TimeProvider time;

    public record Counts(long openPurchaseOrders, long quarantine, long availableMaterialBatches,
                         long wipOrders, long pendingRelease, long shipped30d, long openComplaints,
                         long openRecalls, long pendingReturns) {
    }

    /**
     * kind: QC / RELEASE / MO_ISSUE / MO_COMPLETE / LICENSE / NEAR_EXPIRY / EXPIRED_MATERIAL / RECALL / ADVERSE / COMPLAINT / RETURN /
     * OEM_REPORT / DEALER_ORDER / CHANNEL_FAILED / DEVIATION / CAPA / SUPPLIER_AUDIT / RETAIN / FORMULA / FILING / EQUIPMENT；
     * level: warn / info / bad / muted
     */
    public record Todo(String kind, String label, String level, String title, String ref, String detail, String route) {
    }

    public record Summary(Counts counts, List<Todo> todos, List<AuditLog> recent, int todoTotal) {
    }

    /** 总览每类待办最多显示这么多条。几千条放在一个页面上没人看得完，响应还会很大。 */
    static final int MAX_TODOS_PER_KIND = 30;

    /** 给页面显示：每类超出的部分合并成一行“还有 N 条”。todoTotal 仍是全部数量；每日提醒直接用 summary()，不受影响。 */
    public static Summary forDisplay(Summary s) {
        Map<String, Long> totals = s.todos().stream().collect(java.util.stream.Collectors.groupingBy(Todo::kind, java.util.stream.Collectors.counting()));
        Map<String, Integer> shown = new java.util.HashMap<>();
        List<Todo> out = new ArrayList<>();
        for (Todo t : s.todos()) {
            int n = shown.merge(t.kind(), 1, Integer::sum);
            if (n <= MAX_TODOS_PER_KIND) {
                out.add(t);
            } else if (n == MAX_TODOS_PER_KIND + 1) {
                out.add(new Todo("MORE", t.label(), "muted", "还有 " + (totals.get(t.kind()) - MAX_TODOS_PER_KIND) + " 条", "—", "到对应页面查看全部", t.route()));
            }
        }
        return new Summary(s.counts(), out, s.recent(), s.todoTotal());
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        LocalDate today = time.today();
        long availableMaterial = batches.countMaterialInStock(BatchStatus.RELEASED, ItemType.FG);
        List<MfgOrder> wip = mfgOrders.findByStatusIn(List.of(MoStatus.PLANNED, MoStatus.ISSUED));
        List<RecallView> openRecalls = recalls.open();
        List<ComplaintView> openComplaints = complaints.open();
        long pendingReturns = returns.pendingLines();
        Counts counts = new Counts(
                purchaseOrders.countByStatusIn(List.of(PoStatus.OPEN, PoStatus.PARTIAL)),
                batches.countByStatus(BatchStatus.QUARANTINE),
                availableMaterial,
                wip.size(),
                batches.countByStatus(BatchStatus.PENDING_RELEASE),
                salesOrders.countByOrderDateGreaterThanEqual(today.minusDays(30)),
                openComplaints.size(),
                openRecalls.size(),
                pendingReturns);

        List<Todo> todos = new ArrayList<>();
        for (RecallView r : openRecalls) {
            todos.add(new Todo("RECALL", "召回进行中", "bad", r.itemName(), r.batchNo(),
                    r.recallNo() + " · 已通知 " + r.notifiedCount() + "/" + r.notices().size() + " 个客户 · 已回收 "
                            + plain(r.recoveredQty()) + "/" + plain(r.shippedQty().subtract(r.returnedQty())), "/quality-events?tab=recalls&recall=" + r.id()));
        }
        for (ComplaintView c : openComplaints) {
            if ("PENDING".equals(c.reportState()) || "OVERDUE".equals(c.reportState())) {
                boolean overdue = "OVERDUE".equals(c.reportState());
                todos.add(new Todo("ADVERSE", overdue ? "不良反应上报已超期" : "不良反应待上报", overdue ? "bad" : "warn",
                        c.itemName() + "（" + c.severityLabel() + "）", c.complaintNo(),
                        (overdue ? "应于 " : "须于 ") + c.reportDeadline() + " 前上报", "/quality-events"));
            } else {
                todos.add(new Todo("COMPLAINT", "投诉待处理", "muted", c.itemName(), c.complaintNo(),
                        c.categoryLabel() + " · " + c.channel(), "/quality-events"));
            }
        }
        addQualitySystemTodos(todos);
        Map<Long, MfgOrder> wipById = wip.stream().collect(java.util.stream.Collectors.toMap(MfgOrder::getId, m -> m));
        for (OemReport r : oemReports.findByStatus(OemReportStatus.SUBMITTED)) {
            MfgOrder m = wipById.get(r.getMoId());
            if (m != null) {
                todos.add(new Todo("OEM_REPORT", "受托厂已申报完工", "info", lookup.item(m.getFgItemId()).getName(),
                        m.getMoNo(), "产量 " + plain(r.getActualQty()) + "，出厂报告 " + r.getReleaseReportNo() + "，待确认入库",
                        "/production"));
            }
        }
        long dealerPending = dealers.pendingCount();
        if (dealerPending > 0) {
            todos.add(new Todo("DEALER_ORDER", "经销商订单待审核", "warn", dealerPending + " 张经销商订货单", "—",
                    "审核通过即按先到期先出生成出库单", "/dealer-orders"));
        }
        // 供应商已发货等收货；到了交期还没到齐的采购单（交期看供应商回复，没回复看采购期望）
        List<com.cosmetic.scm.purchase.PoShipment> openShipments = poShipments.findByStatusOrderById(com.cosmetic.scm.domain.ShipmentStatus.OPEN);
        if (!openShipments.isEmpty()) {
            Map<Long, com.cosmetic.scm.purchase.PurchaseOrder> pos = purchaseOrders.findAllById(openShipments.stream()
                    .map(com.cosmetic.scm.purchase.PoShipment::getPoId).toList()).stream()
                    .collect(java.util.stream.Collectors.toMap(com.cosmetic.scm.purchase.PurchaseOrder::getId, p -> p));
            for (com.cosmetic.scm.purchase.PoShipment s : openShipments) {
                com.cosmetic.scm.purchase.PurchaseOrder po = pos.get(s.getPoId());
                todos.add(new Todo("SHIPMENT", "供应商已发货", "info", lookup.partner(po.getSupplierId()).getName(), s.getShipmentNo(),
                        "采购单 " + po.getPoNo() + "，发货日期 " + s.getShipDate(), "/purchase"));
            }
        }
        for (com.cosmetic.scm.purchase.PurchaseOrder po : purchaseOrders.findOverdue(List.of(PoStatus.OPEN, PoStatus.PARTIAL), today)) {
            todos.add(new Todo("PO_LATE", "采购单超交期", "warn", lookup.partner(po.getSupplierId()).getName(), po.getPoNo(),
                    "交期 " + (po.getPromisedDate() != null ? po.getPromisedDate() : po.getExpectedDate()) + "，还没到齐", "/purchase"));
        }
        for (com.cosmetic.scm.sourcing.Rfq r : rfqRepo.findWaitingAward(com.cosmetic.scm.domain.RfqStatus.OPEN, com.cosmetic.scm.domain.RfqStatus.CLOSED, today)) {
            todos.add(new Todo("RFQ_AWARD", "询价待定标", "warn", r.getTitle(), r.getRfqNo(), "报价已截止，请比价定标", "/sourcing"));
        }
        for (com.cosmetic.scm.settlement.SupplierStatement s : statementRepo.findByStatusInOrderById(List.of(com.cosmetic.scm.domain.StatementStatus.DISPUTED, com.cosmetic.scm.domain.StatementStatus.INVOICED, com.cosmetic.scm.domain.StatementStatus.APPROVED))) {
            String name = lookup.partner(s.getSupplierId()).getName();
            switch (s.getStatus()) {
                case DISPUTED -> todos.add(new Todo("STATEMENT_DISPUTE", "对账单有异议", "warn", name, s.getStatementNo(), s.getDisputeNote(), "/settlement"));
                case INVOICED -> todos.add(new Todo("STATEMENT_APPROVE", "对账单待审批", "warn", name, s.getStatementNo(), "发票 " + s.getInvoiceNo() + "，合计 " + s.getTotalAmount().toPlainString(), "/settlement"));
                default -> todos.add(new Todo("STATEMENT_PAY", "对账单待付款", "info", name, s.getStatementNo(), "未付 " + s.getTotalAmount().subtract(s.getPaidAmount()).toPlainString(), "/settlement"));
            }
        }
        long channelFailed = integration.failedCount();
        if (channelFailed > 0) {
            todos.add(new Todo("CHANNEL_FAILED", "电商订单出库失败", "bad", channelFailed + " 张电商订单", "—",
                    "查看失败原因，补货后重试", "/integration"));
        }
        if (pendingReturns > 0) {
            todos.add(new Todo("RETURN", "退货待处置", "warn", pendingReturns + " 行退货在隔离区", "—",
                    "由质量人员判定转可售或报废", "/returns"));
        }
        for (BatchView b : inventory.listBatches(List.of(BatchStatus.QUARANTINE), null)) {
            todos.add(new Todo("QC", "物料待检", "warn", b.itemName(), b.batchNo(),
                    plain(b.qtyIn()) + " " + b.unit() + " · " + nullToDash(b.partnerName()), "/quality"));
        }
        for (BatchView b : inventory.listBatches(List.of(BatchStatus.PENDING_RELEASE), null)) {
            todos.add(new Todo("RELEASE", "待上市放行", "info", b.itemName(), b.batchNo(),
                    plain(b.qtyIn()) + " · " + nullToDash(b.partnerName()), "/quality"));
        }
        Map<Long, Item> im = lookup.itemMap();
        for (MfgOrder m : wip) {
            boolean planned = m.getStatus() == MoStatus.PLANNED;
            todos.add(new Todo(planned ? "MO_ISSUE" : "MO_COMPLETE", planned ? "待发料" : "待完工", "muted",
                    im.get(m.getFgItemId()).getName() + " × " + plain(m.getPlannedQty()), m.getMoNo(),
                    "计划 " + m.getPlannedDate(), "/production"));
        }
        for (PartnerView p : master.listPartners()) {
            if (p.kind() == PartnerKind.CUSTOMER) {
                continue;
            }
            if ("EXPIRED".equals(p.licenseState())) {
                todos.add(new Todo("LICENSE", "资质已过期", "bad", p.name(), p.licenseNo(),
                        "已于 " + p.licenseExpiry() + " 过期，系统已停止向其下单", "/master"));
            } else if ("EXPIRING".equals(p.licenseState())) {
                todos.add(new Todo("LICENSE", "资质 " + p.licenseDaysLeft() + " 天后到期", "warn", p.name(), p.licenseNo(),
                        "有效期至 " + p.licenseExpiry(), "/master"));
            }
        }
        // 查全部在库的已放行成品，不是“最近 N 个批次”：最老的批次才最可能临期
        // 先按日期过滤再组装视图：组装要查库存，几千个批次全组装一遍很慢
        List<com.cosmetic.scm.inventory.Batch> nearBatches = batches.findInStockByStatusAndItemType(BatchStatus.RELEASED, ItemType.FG).stream()
                .filter(b -> {
                    Double r = com.cosmetic.scm.domain.rules.ShelfLife.remainingRatio(b.getMfgDate(), b.getExpDate(), today);
                    return r != null && r < NEAR_EXPIRY_RATIO;
                }).toList();
        for (BatchView b : inventory.toViews(nearBatches)) {
            if (b.shelfRatio() != null && b.shelfRatio() < NEAR_EXPIRY_RATIO) {
                boolean expired = com.cosmetic.scm.domain.rules.ShelfLife.isExpired(b.expDate(), today);
                todos.add(new Todo("NEAR_EXPIRY", expired ? "成品已过期" : "成品临期", "bad", b.itemName(), b.batchNo(),
                        "剩余 " + plain(b.onHand()) + " · 效期至 " + b.expDate(), "/stock"));
            }
        }
        // 过期物料不能发料，但也不会有人主动看到：到发料时才报缺货就晚了
        for (BatchView b : inventory.toViews(batches.findExpiredMaterialsInStock(BatchStatus.RELEASED, today))) {
            todos.add(new Todo("EXPIRED_MATERIAL", "物料已过期", "bad", b.itemName(), b.batchNo(),
                    "剩余 " + plain(b.onHand()) + " " + b.unit() + " · 效期至 " + b.expDate() + "，请报废或判定处理", "/stock"));
        }
        return new Summary(counts, todos, audit.list(null, 8), todos.size());
    }

    private void addQualitySystemTodos(List<Todo> todos) {
        for (DeviationService.DeviationView d : deviations.open()) {
            boolean major = d.level() != com.cosmetic.scm.domain.DeviationLevel.MINOR;
            todos.add(new Todo("DEVIATION", d.overdue() ? "偏差调查已超期" : "偏差处理中", d.overdue() ? "bad" : major ? "warn" : "muted",
                    d.title(), d.deviationNo(), d.levelLabel() + " · 期限 " + d.dueDate()
                    + (major && d.batchNo() != null ? " · 批次 " + d.batchNo() + " 不能放行" : ""), "/qms"));
        }
        for (DeviationService.CapaView c : deviations.openCapas()) {
            if (c.overdue()) {
                todos.add(new Todo("CAPA", "CAPA 已超期", "bad", c.description(), c.capaNo(), "负责人 " + c.owner() + " · 期限 " + c.dueDate(), "/qms?tab=capas"));
            } else if (c.status() == com.cosmetic.scm.domain.CapaStatus.DONE) {
                todos.add(new Todo("CAPA", "CAPA 待验证", "warn", c.description(), c.capaNo(), "已由 " + c.doneBy() + " 完成，待质量验证有效性", "/qms?tab=capas"));
            }
        }
        for (SupplierAuditService.PartnerRow p : supplierAudits.partnerRows()) {
            if ("OVERDUE".equals(p.dueState())) {
                todos.add(new Todo("SUPPLIER_AUDIT", "供应商审核已超期", "bad", p.name(), p.code(), "应于 " + p.nextAuditDate() + " 前审核", "/qms?tab=audits"));
            } else if ("DUE".equals(p.dueState())) {
                todos.add(new Todo("SUPPLIER_AUDIT", "供应商审核将到期", "warn", p.name(), p.code(), p.dueDaysLeft() + " 天后到期（" + p.nextAuditDate() + "）", "/qms?tab=audits"));
            }
        }
        for (RetainService.SampleView s : retains.dueForDestruction()) {
            todos.add(new Todo("RETAIN", "留样待销毁", "muted", s.itemName(), s.batchNo(), "保存期已于 " + s.retainUntil() + " 届满，剩余 " + s.remainingQty() + " 件", "/qms?tab=retains"));
        }
        for (EquipmentService.EquipmentView e : equipment.list()) {
            if (e.status() == com.cosmetic.scm.domain.EquipmentStatus.STOPPED) {
                continue;
            }
            if ("OVERDUE".equals(e.dueState())) {
                todos.add(new Todo("EQUIPMENT", "设备校准已过期", "bad", e.name(), e.code(),
                        (e.nextDue() == null ? "还没有校准记录" : "已于 " + e.nextDue() + " 到期") + "，不能用于批记录", "/qms?tab=equipment"));
            } else if ("DUE".equals(e.dueState())) {
                todos.add(new Todo("EQUIPMENT", "设备校准将到期", "warn", e.name(), e.code(), e.dueDaysLeft() + " 天后到期（" + e.nextDue() + "）", "/qms?tab=equipment"));
            }
        }
        long pendingFormula = formulas.pendingCount();
        if (pendingFormula > 0) {
            todos.add(new Todo("FORMULA", "配方变更待签批", "warn", pendingFormula + " 个配方版本", "—", "由质量安全负责人签批后生效", "/formulas"));
        }
        for (FilingService.FilingView f : filings.expiring()) {
            boolean expired = f.expiryDaysLeft() < 0;
            todos.add(new Todo("FILING", expired ? "注册证已到期" : "注册证将到期", expired ? "bad" : "warn", f.fgName(), f.filingNo(),
                    (expired ? "已于 " : "有效期至 ") + f.expiryDate() + (expired ? " 到期，不能上市放行" : ""), "/formulas?tab=filings"));
        }
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static String nullToDash(String s) {
        return s == null ? "—" : s;
    }
}
