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
import com.cosmetic.scm.domain.StatementStatus;
import com.cosmetic.scm.domain.rules.SettlementRule;
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
import com.cosmetic.scm.settlement.SupplierStatement;
import com.cosmetic.scm.supplierreturn.SupplierReturn;
import com.cosmetic.scm.supplierreturn.SupplierReturnService;

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
    private final com.cosmetic.scm.master.RegulatoryService regulatory;
    private final com.cosmetic.scm.plm.ComplianceService compliance;
    private final com.cosmetic.scm.plm.StabilityService stability;
    private final com.cosmetic.scm.plm.StabilityChangeService stabilityChanges;
    private final com.cosmetic.scm.quality.QualitySpecService qualitySpecs;
    private final com.cosmetic.scm.sourcing.SupplierPriceService supplierPrices;
    private final com.cosmetic.scm.plm.NpdService npd;
    private final com.cosmetic.scm.plm.DocControlService docControl;
    private final com.cosmetic.scm.plm.ChangeService changeControl;
    private final com.cosmetic.scm.plm.ArtworkService artworkService;
    private final com.cosmetic.scm.plm.ArtworkProofService artworkProofs;
    private final com.cosmetic.scm.supplierreturn.SupplierReturnRepository supplierReturns;
    private final SupplierReturnService supplierReturnService;
    private final com.cosmetic.scm.settlement.SupplierStatementRepository statementRepo;
    private final com.cosmetic.scm.settlement.CustomerStatementRepository customerStatementRepo;
    private final BatchRepository batches;
    private final MfgOrderRepository mfgOrders;
    private final SalesOrderRepository salesOrders;
    private final com.cosmetic.scm.sales.SoLineRepository soLines;
    private final InventoryService inventory;
    private final MasterService master;
    private final MasterLookup lookup;
    private final ComplaintService complaints;
    private final RecallService recalls;
    private final ReturnService returns;
    private final com.cosmetic.scm.quality.QualityService quality;
    private final com.cosmetic.scm.production.ProductionService production;
    private final OemReportRepository oemReports;
    private final DealerService dealers;
    private final IntegrationService integration;
    private final com.cosmetic.scm.serial.SerialService serials;
    private final DeviationService deviations;
    private final SupplierAuditService supplierAudits;
    private final RetainService retains;
    private final FormulaService formulas;
    private final FilingService filings;
    private final EquipmentService equipment;
    private final AuditService audit;
    private final com.cosmetic.scm.qms.GmpSelfCheckService selfChecks;
    private final com.cosmetic.scm.qms.QsoService qso;
    private final com.cosmetic.scm.oemcontract.OemContractService oemContracts;
    private final com.cosmetic.scm.oemsupervision.OemSupervisionService oemSupervision;
    private final com.cosmetic.scm.storage.StorageMonitorService storageMonitor;
    private final com.cosmetic.scm.recall.RecallDrillService recallDrills;
    private final com.cosmetic.scm.sales.SalesRecordService salesRecords;
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
        // 总览所有内部岗位都能看，这里的文字不能带价格和金额（SourcingSettlementTest 会扫）
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
        var receiptDisputes = poShipments.findByReceiptResolvedAtIsNullAndReceiptDisputedAtIsNotNullOrderByReceiptDisputedAtAscIdAsc();
        if (!receiptDisputes.isEmpty()) {
            var disputedOrders = purchaseOrders.findAllById(receiptDisputes.stream().map(s -> s.getPoId()).distinct().toList())
                    .stream().collect(java.util.stream.Collectors.toMap(com.cosmetic.scm.purchase.PurchaseOrder::getId, p -> p));
            for (var notice : receiptDisputes) {
                var po = disputedOrders.get(notice.getPoId());
                todos.add(new Todo("RECEIPT_DISPUTE", "收货异议待处理", "warn", lookup.partner(po.getSupplierId()).getName(),
                        notice.getShipmentNo(), "采购单 " + po.getPoNo(), "/purchase?receiptDispute=" + notice.getId()));
            }
        }
        // 法规目录变了，生效配方可能已经不合规
        for (com.cosmetic.scm.master.RegulatoryService.Violation v : regulatory.violations()) {
            todos.add(new Todo("REG_VIOLATION", "配方不符合法规目录", "bad", v.fgName(), v.fgCode(), v.message(), "/compliance?tab=regulatory"));
        }
        // 配方变了，旧版本的合规资料要重新评估。从没登记过的只算一条总数，上线初期不要刷屏
        long complianceMissing = 0;
        for (com.cosmetic.scm.plm.ComplianceService.ProductStatus p : compliance.statuses()) {
            if ("STALE".equals(p.safety()) || "STALE".equals(p.label())) {
                todos.add(new Todo("COMPLIANCE_STALE", "合规资料要重新评估", "warn", p.fgName(), p.fgCode(), "配方已变为 V" + p.versionNo() + "，安全评估或标签还是旧版本的", "/compliance"));
            } else if (p.versionId() != null && !p.complete()) {
                complianceMissing++;
            }
        }
        if (complianceMissing > 0) {
            todos.add(new Todo("COMPLIANCE_MISSING", "合规资料不全", "info", complianceMissing + " 个成品", "—", "缺已签批的安全评估或标签", "/compliance"));
        }
        for (com.cosmetic.scm.plm.DocControlService.DocRow d : docControl.pendingApproval()) {
            todos.add(new Todo("DOC_APPROVE", "受控文件待签批", "warn", d.title(), d.docCode(), "新版本等待质量安全负责人签批", "/docs"));
        }
        for (com.cosmetic.scm.plm.ArtworkService.ArtworkView a : artworkService.pending()) {
            todos.add(new Todo("ARTWORK_REVIEW", "标签设计稿待评审", "warn", a.pkName(), a.artworkNo(), a.fgName() + "，第 " + a.versionNo() + " 版", "/artworks"));
        }
        for (com.cosmetic.scm.plm.ArtworkService.ArtworkView a : artworkService.stale()) {
            todos.add(new Todo("ARTWORK_STALE", "标签设计稿要重新评审", "warn", a.pkName(), a.artworkNo(), a.fgName() + " 的配方已变，成分表可能要改", "/artworks"));
        }
        for (com.cosmetic.scm.plm.ArtworkProofService.ProofView p : artworkProofs.pending()) {
            todos.add(new Todo("PROOF_SIGN", "包材打样待签样", "warn", p.pkName(), p.proofNo(), p.supplierName() + " 第 " + p.roundNo() + " 次打样，设计稿第 " + p.artworkVersionNo() + " 版", "/artworks"));
        }
        for (com.cosmetic.scm.plm.DocControlService.DocRow d : docControl.ackPending()) {
            todos.add(new Todo("DOC_ACK", d.ackOverdue() ? "受控文件确认已超期" : "受控文件待确认", d.ackOverdue() ? "bad" : "info", d.title(), d.docCode(),
                    "第 " + d.currentRev() + " 版还有 " + d.ackPending() + " 人没确认已读", "/docs"));
        }
        for (com.cosmetic.scm.plm.DocControlService.DocRow d : docControl.assessPending()) {
            todos.add(new Todo("DOC_ASSESS", d.assessOverdue() ? "受控文件考核已超期" : "受控文件待考核", d.assessOverdue() ? "bad" : "info", d.title(), d.docCode(),
                    "第 " + d.currentRev() + " 版还有 " + d.assessPending() + " 人确认了还没考核通过", "/docs"));
        }
        // 销售记录（检查要点第 19 项）：近 30 天出库里缺内容的行
        var sr = salesRecords.records(null, null);
        if (sr.incompleteRows() > 0) {
            todos.add(new Todo("SALES_RECORD_GAPS", "销售记录不全", "warn", "近 " + com.cosmetic.scm.sales.SalesRecordService.DEFAULT_DAYS + " 天 " + sr.incompleteRows() + " 行",
                    "—", String.join("，", sr.gapSummary()), "/sales?tab=records"));
        }
        for (com.cosmetic.scm.qms.QsoService.DelegationView d : qso.expiringSoon()) {
            todos.add(new Todo("QSO_DELEGATION_EXPIRING", "协助履职授权快到期", "warn", d.delegateName(), d.delegationNo(), d.dutyLabels() + "，" + d.validTo() + " 到期", "/qso"));
        }
        for (com.cosmetic.scm.qms.QsoService.UserCheck u : qso.unauthorized()) {
            todos.add(new Todo("QSO_UNAUTHORIZED", "质量安全负责人角色没有任职或授权", "bad", u.displayName() == null ? u.username() : u.displayName(), u.username(),
                    "不是现任，也没有生效的协助履职授权，却能签批放行", "/qso"));
        }
        // 委托生产合同（检查要点第 8 项）
        for (com.cosmetic.scm.oemcontract.OemContractService.ContractView c : oemContracts.pendingApproval()) {
            todos.add(new Todo("OEM_CONTRACT_APPROVE", "委托生产合同待签批", "warn", c.factoryName(), c.contractNo(),
                    c.products().size() + " 个成品，" + c.validFrom() + " 至 " + c.validTo() + "，" + c.submittedBy() + " 提交", "/oem-contracts"));
        }
        for (com.cosmetic.scm.oemcontract.OemContractService.ContractView c : oemContracts.expiringSoon()) {
            todos.add(new Todo("OEM_CONTRACT_EXPIRING", "委托生产合同快到期", "warn", c.factoryName(), c.contractNo(),
                    c.validTo() + " 到期，覆盖 " + c.productNames() + "，请续签", "/oem-contracts"));
        }
        for (com.cosmetic.scm.oemcontract.OemContractService.OrderCheck o : oemContracts.uncoveredOpenOrders()) {
            todos.add(new Todo("OEM_CONTRACT_MISSING", "委托单没有委托生产合同", "warn", o.fgName(), o.moNo(),
                    "受托厂 " + o.factoryName() + "，下单时没有覆盖这个成品的有效合同", "/oem-contracts?tab=orders"));
        }
        // 受托生产监督（检查要点第 9、18 项）
        for (com.cosmetic.scm.oemsupervision.OemSupervisionService.RecordView r : oemSupervision.pendingReview()) {
            todos.add(new Todo("OEM_SUPERVISION_REVIEW", "受托生产监督待审核", "warn", r.factoryName(), r.supervisionNo(),
                    r.supervisedOn() + " " + r.methodLabel() + "，" + r.conclusionLabel() + "，" + r.submittedBy() + " 提交", "/oem-supervision"));
        }
        for (com.cosmetic.scm.oemsupervision.OemSupervisionService.FactoryRow f : oemSupervision.dueFactories()) {
            todos.add(new Todo("OEM_SUPERVISION_DUE", "受托厂监督到期", "warn", f.factoryName(), f.factoryCode(),
                    f.openOrders() + " 张委托单在做，" + (f.lastSupervised() == null ? "还没有监督记录" : "最近一次监督 " + f.lastSupervised() + "，超过 "
                            + oemSupervision.intervalDays() + " 天"), "/oem-supervision?tab=factories"));
        }
        // 贮存区域温湿度（检查要点第 16、20 项）：仍超限的、昨天漏记的
        for (com.cosmetic.scm.storage.StorageMonitorService.TodoRow r : storageMonitor.todos()) {
            boolean out = "OUT".equals(r.kind());
            todos.add(new Todo(out ? "STORAGE_EXCURSION" : "STORAGE_RECORD_MISSING", out ? "贮存区域温湿度超限" : "温湿度漏记", out ? "bad" : "warn",
                    r.areaName(), r.areaCode(), r.detail(), out ? "/storage-env?tab=excursions" : "/storage-env"));
        }
        // 召回演练（检查要点第 24 项）：待审核的、超过周期没有审核过的演练
        for (com.cosmetic.scm.recall.RecallDrillService.DrillView d : recallDrills.pendingReview()) {
            todos.add(new Todo("RECALL_DRILL_REVIEW", "召回演练待审核", "warn", d.itemName(), d.drillNo(),
                    "批号 " + d.batchNo() + "，" + (d.met() ? "达标" : "没达标") + "，用时 " + d.elapsedHours() + " 小时，" + d.submittedBy() + " 提交",
                    "/quality-events?tab=drills"));
        }
        recallDrills.due().ifPresent(d -> todos.add(new Todo("RECALL_DRILL_DUE", "召回演练到期", "warn", "召回演练", d.ref(),
                d.detail() + "，请选一个已发货的成品批次做一次模拟召回", "/quality-events?tab=drills")));
        for (com.cosmetic.scm.qms.GmpSelfCheckService.CheckRow r : selfChecks.waitingApproval()) {
            todos.add(new Todo("SELF_CHECK_APPROVE", "GMP 自查待签批", "warn", r.editionLabel(), r.checkNo(), "结论：" + r.conclusionLabel() + "，" + r.submittedBy() + " 提交", "/self-checks"));
        }
        // 年度自查：做过的才按 12 个月提醒（到期前 30 天开始）；从没做过的只在上线检查提示，不刷屏
        selfChecks.lastApproved().ifPresent(last -> {
            java.time.LocalDate due = last.getApprovedAt().toLocalDate().plusMonths(12);
            if (!selfChecks.anyOpen() && !due.minusDays(30).isAfter(today)) {
                boolean late = due.isBefore(today);
                todos.add(new Todo("SELF_CHECK_DUE", late ? "年度 GMP 自查已超期" : "年度 GMP 自查快到期", late ? "bad" : "warn", "上次 " + last.getCheckNo(),
                        last.getCheckNo(), "应在 " + due + " 前完成新一年的自查", "/self-checks"));
            }
        });
        for (com.cosmetic.scm.plm.DocControlService.DocRow d : docControl.reviewDue()) {
            todos.add(new Todo("DOC_REVIEW", d.reviewOverdue() ? "受控文件复审已超期" : "受控文件复审快到期", d.reviewOverdue() ? "bad" : "warn", d.title(), d.docCode(), "复审期限 " + d.reviewDue(), "/docs"));
        }
        for (com.cosmetic.scm.plm.DocControlService.DocRow d : docControl.copiesToRetrieve()) {
            todos.add(new Todo("DOC_COPY_RETRIEVE", d.retrieveOverdue() ? "纸质受控文件回收已超期" : "纸质受控文件待回收", d.retrieveOverdue() ? "bad" : "warn", d.title(), d.docCode(),
                    d.copiesToRetrieve() + " 份旧版或已作废的纸质副本要收回，期限 " + d.retrieveDue(), "/docs"));
        }
        for (com.cosmetic.scm.plm.ChangeService.ChangeRow c : changeControl.byStatus(com.cosmetic.scm.domain.ChangeStatus.SUBMITTED)) {
            todos.add(new Todo("CHANGE_ASSESS", "变更待评估", "warn", c.title(), c.changeNo(), c.changeTypeLabel() + "，" + c.createdBy() + " 提出", "/changes"));
        }
        for (com.cosmetic.scm.plm.ChangeService.ChangeRow c : changeControl.byStatus(com.cosmetic.scm.domain.ChangeStatus.ASSESSED)) {
            todos.add(new Todo("CHANGE_APPROVE", "变更待批准", "warn", c.title(), c.changeNo(), "风险" + c.riskLabel() + "，" + c.actionsTotal() + " 项行动", "/changes"));
        }
        for (com.cosmetic.scm.plm.ChangeService.ChangeRow c : changeControl.byStatus(com.cosmetic.scm.domain.ChangeStatus.APPROVED)) {
            if (c.actionsOpen() == 0) {
                todos.add(new Todo("CHANGE_CLOSE", "变更待关闭", "info", c.title(), c.changeNo(), "行动都完成了，请确认效果后关闭", "/changes"));
            } else if (c.actionsOverdue() > 0) {
                todos.add(new Todo("CHANGE_ACTION", "变更行动超期", "bad", c.title(), c.changeNo(), c.actionsOverdue() + " 项行动超过期限没完成", "/changes"));
            }
        }
        for (com.cosmetic.scm.plm.NpdService.ProjectRow p : npd.byStatus(com.cosmetic.scm.domain.NpdStatus.PROPOSED)) {
            todos.add(new Todo("NPD_PROPOSED", "新品立项待批准", "info", p.name(), p.projectNo(), p.category() + "，计划 " + p.targetLaunch() + " 上市", "/npd"));
        }
        for (com.cosmetic.scm.plm.NpdService.ProjectRow p : npd.byStatus(com.cosmetic.scm.domain.NpdStatus.LAUNCH_REVIEW)) {
            todos.add(new Todo("NPD_LAUNCH", "新品上市待批准", "warn", p.name(), p.projectNo(), "上市检查已通过，请质量安全负责人批准", "/npd"));
        }
        for (com.cosmetic.scm.plm.NpdService.ProjectRow p : npd.late(today)) {
            todos.add(new Todo("NPD_LATE", "新品超过计划上市日期", "info", p.name(), p.projectNo(), "计划 " + p.targetLaunch() + "，现在" + p.statusLabel(), "/npd"));
        }
        for (com.cosmetic.scm.quality.QualitySpecService.SpecView s : qualitySpecs.waitingSignature()) {
            todos.add(new Todo("SPEC_REVIEW", "质量标准待签批", "warn", s.itemName(), s.specNo(), "V" + s.versionNo() + "：" + s.changeNote(), "/specs"));
        }
        if (com.cosmetic.scm.security.PriceAccess.allowed()) {
            for (com.cosmetic.scm.sourcing.SupplierPriceService.PriceView p : supplierPrices.expiringSoon(15)) {
                boolean expired = p.validTo().isBefore(today);
                todos.add(new Todo("PRICE_EXPIRING", expired ? "供应商协议价已过期" : "供应商协议价即将到期",
                        expired ? "bad" : "warn", p.supplierName() + " · " + p.itemName(),
                        p.supplierCode() + "/" + p.itemCode(), "有效期至 " + p.validTo(), "/purchase"));
            }
        }
        // 稳定性：错过取样时间这个点就补不回来了，提前 7 天提醒
        for (com.cosmetic.scm.plm.StabilityService.DuePull p : stability.due(today.plusDays(7))) {
            boolean late = p.dueDate().isBefore(today);
            todos.add(new Todo("STABILITY_DUE", late ? "稳定性取样已超期" : "稳定性取样快到期", late ? "bad" : "warn", p.fgName(), p.studyNo(), p.label() + "，计划 " + p.dueDate(), "/stability"));
        }
        for (com.cosmetic.scm.plm.StabilityService.StudyRow s : stability.waitingConclusion()) {
            todos.add(new Todo("STABILITY_CONCLUDE", "稳定性试验待写结论", "warn", s.fgName(), s.studyNo(), "时间点都完成了，请写结论", "/stability"));
        }
        for (com.cosmetic.scm.plm.StabilityChangeService.PendingCorrection c : stabilityChanges.pending()) {
            todos.add(new Todo("STABILITY_CORRECTION", "稳定性结果更正待审批", "warn", c.fgName(), c.studyNo(), c.pullLabel() + " " + c.testName() + "，" + c.requestedBy() + " 申请", "/stability"));
        }
        for (com.cosmetic.scm.plm.StabilityService.StudyRow s : stability.waitingSignature()) {
            todos.add(new Todo("STABILITY_REVIEW", "稳定性结论待签批", "warn", s.fgName(), s.studyNo(), "支持保质期 " + s.supportedMonths() + " 个月", "/stability"));
        }
        // 没做过的不进待办，上线初期不要刷屏，由上线检查提示
        for (com.cosmetic.scm.plm.StabilityService.ProductCoverage c : stability.coverage()) {
            if ("SHORT".equals(c.state())) {
                todos.add(new Todo("STABILITY_GAP", "保质期超过稳定性依据", "warn", c.fgName(), c.fgCode(), "标的 " + c.shelfMonths() + " 个月，依据只有 " + c.supportedMonths() + " 个月", "/stability?tab=products"));
            } else if ("STALE".equals(c.state())) {
                todos.add(new Todo("STABILITY_GAP", "稳定性依据要重新做", "warn", c.fgName(), c.fgCode(), "配方已变为 V" + c.versionNo() + "，已签批的结论是旧版本的", "/stability?tab=products"));
            }
        }
        for (com.cosmetic.scm.supplierreturn.SupplierReturn r : supplierReturns.findByStatusOrderById(com.cosmetic.scm.domain.SupplierReturnStatus.PENDING)) {
            todos.add(new Todo("SUPPLIER_RETURN", "退供待签批", "warn", lookup.partner(r.getSupplierId()).getName(), r.getReturnNo(), r.getReason(), "/supplier-returns?no=" + r.getReturnNo()));
        }
        for (com.cosmetic.scm.supplierreturn.SupplierReturn r : supplierReturns.findByStatusAndShippedAtIsNullAndSupplierConfirmedAtIsNullOrderById(com.cosmetic.scm.domain.SupplierReturnStatus.DONE)) {
            todos.add(new Todo("SUPPLIER_RETURN_SHIP", "退供待登记物流", "warn", lookup.partner(r.getSupplierId()).getName(),
                    r.getReturnNo(), "质量已签批，请登记承运商和运单号", "/supplier-returns?no=" + r.getReturnNo()));
        }
        for (SupplierReturn r : supplierReturnService.awaitingOfflineReceipts()) {
            todos.add(new Todo("SUPPLIER_RETURN_OFFLINE", "退供待代录签收", "warn", lookup.partner(r.getSupplierId()).getName(),
                    r.getReturnNo(), "请在收到供应商回单后登记签收", "/supplier-returns?no=" + r.getReturnNo()));
        }
        for (com.cosmetic.scm.sourcing.Rfq r : rfqRepo.findWaitingAward(com.cosmetic.scm.domain.RfqStatus.OPEN, com.cosmetic.scm.domain.RfqStatus.CLOSED, today)) {
            todos.add(new Todo("RFQ_AWARD", "询价待定标", "warn", r.getTitle(), r.getRfqNo(), "报价已截止，请比价定标", "/sourcing"));
        }
        for (SupplierStatement s : statementRepo.findByStatusInOrderById(List.of(StatementStatus.DISPUTED, StatementStatus.INVOICED, StatementStatus.APPROVED))) {
            String name = lookup.partner(s.getSupplierId()).getName();
            String route = "/settlement?no=" + s.getStatementNo();
            switch (s.getStatus()) {
                case DISPUTED -> todos.add(new Todo("STATEMENT_DISPUTE", "对账单有异议", "warn", name, s.getStatementNo(), s.getDisputeNote(), route));
                case INVOICED -> todos.add(new Todo("STATEMENT_APPROVE", "对账单待审批", "warn", name, s.getStatementNo(), "发票 " + s.getInvoiceNo() + "，请财务审批", route));
                case APPROVED -> {
                    if (s.outstandingAmount().signum() != 0) {
                        todos.add(statementCashTodo(s, name, route, today));
                    }
                }
                default -> { }
            }
        }
        for (SupplierStatement s : statementRepo.findByStatusAndInvoiceReviewVersionGreaterThanOrderById(StatementStatus.CONFIRMED, 0)) {
            todos.add(new Todo("STATEMENT_INVOICE_FIX", "对账发票待更正", "warn", lookup.partner(s.getSupplierId()).getName(), s.getStatementNo(),
                    "请跟进供应商重提发票，或由采购代登记", "/settlement?no=" + s.getStatementNo()));
        }
        for (com.cosmetic.scm.settlement.CustomerStatement ar : customerStatementRepo.findByStatusInOrderById(List.of("APPROVED"))) {
            if (ar.outstandingAmount().signum() <= 0) {
                continue;
            }
            String cname = lookup.partner(ar.getCustomerId()).getName();
            LocalDate due = ar.getDueDate();
            long lateDays = SettlementRule.overdueDays(due, today, false);
            if (lateDays > 0) {
                todos.add(new Todo("AR_COLLECT", "客户回款已逾期", "bad", cname, ar.getStatementNo(),
                        "到期日 " + due + "，已逾期 " + lateDays + " 天", "/settlement?tab=ar"));
            } else if (today.equals(due)) {
                todos.add(new Todo("AR_COLLECT", "客户回款今日到期", "warn", cname, ar.getStatementNo(),
                        "请核对并登记客户回款", "/settlement?tab=ar"));
            } else if (due != null && !due.isAfter(today.plusDays(7))) {
                todos.add(new Todo("AR_COLLECT", "客户回款即将到期", "info", cname, ar.getStatementNo(),
                        "到期日 " + due, "/settlement?tab=ar"));
            }
        }
        // 出库行没有单价：补上之前不进应收、不占信用额度（待办不带金额）
        for (Object[] r : soLines.countUnpricedUnbilledByCustomer()) {
            com.cosmetic.scm.master.Partner c = lookup.partner((Long) r[0]);
            todos.add(new Todo("SO_UNPRICED", "出库行没有单价", "warn", c.getName(), c.getCode(),
                    r[1] + " 行出库没有单价（最早 " + r[2] + "），补上前不进应收、不占信用额度", "/sales?tab=unpriced&customerId=" + r[0]));
        }
        long channelFailed = integration.failedCount();
        if (channelFailed > 0) {
            todos.add(new Todo("CHANNEL_FAILED", "电商订单出库失败", "bad", channelFailed + " 张电商订单", "—",
                    "查看失败原因，补货后重试", "/integration"));
        }
        long serialAnomalies = serials.suspiciousCount();
        if (serialAnomalies > 0) {
            todos.add(new Todo("SERIAL_ANOMALY", "疑似异常防伪扫码", "warn", serialAnomalies + " 个防伪码高频重复扫码", "—",
                    "单码扫码达到 " + com.cosmetic.scm.serial.SerialService.DEFAULT_ANOMALY_THRESHOLD + " 次及以上，请核查仿冒或窜货风险", "/serials"));
        }
        if (pendingReturns > 0) {
            todos.add(new Todo("RETURN", "退货待处置", "warn", pendingReturns + " 行退货在隔离区", "—",
                    "由质量人员判定转可售或报废", "/returns"));
        }
        long ncrPending = quality.ncrDispositions().stream()
                .filter(r -> !"CLOSED".equals(r.closureState()) && r.onHand() != null && r.onHand().signum() > 0)
                .count();
        if (ncrPending > 0) {
            todos.add(new Todo("NCR_DISPOSITION", "不合格品待处置", "bad", ncrPending + " 个不合格/冻结批次在库", "—",
                    "请及时发起退供、登记偏差或报废清零", "/quality?tab=ncr"));
        }
        long unbilledArReturns = returns.closureSummary().disposedUnbilledArLines();
        if (unbilledArReturns > 0) {
            todos.add(new Todo("RETURN_AR_OFFSET", "客退待冲减应收", "warn", unbilledArReturns + " 行已处置客退未冲减应收", "—",
                    "请到客户应收对账单核对冲减", "/returns"));
        }
        long overuseItems = production.materialVariance().stream()
                .filter(v -> "OVERUSE".equals(v.state()))
                .count();
        if (overuseItems > 0) {
            todos.add(new Todo("MATERIAL_OVERUSE", "生产物料超耗预警", "warn", overuseItems + " 项生产/委外领料超耗", "—",
                    "请核对物料平衡并在委外对账时评估扣款", "/production?tab=variance"));
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

    private Todo statementCashTodo(SupplierStatement s, String supplierName, String route, LocalDate today) {
        boolean refund = s.outstandingAmount().signum() < 0;
        LocalDate due = s.effectiveDueDate();
        long lateDays = SettlementRule.overdueDays(due, today, false);
        String kind = refund ? "STATEMENT_REFUND" : "STATEMENT_PAY";
        String action = refund ? "供应商退款" : "对账单付款";
        if (lateDays > 0) {
            return new Todo(kind, action + "已逾期", "bad", supplierName, s.getStatementNo(), "到期日 " + due + "，已逾期 " + lateDays + " 天", route);
        }
        if (today.equals(due)) {
            return new Todo(kind, action + "今日到期", "warn", supplierName, s.getStatementNo(), "请核对并登记" + (refund ? "退款" : "付款"), route);
        }
        return new Todo(kind, refund ? "待收供应商退款" : "对账单待付款", "info", supplierName, s.getStatementNo(), "到期日 " + due, route);
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
