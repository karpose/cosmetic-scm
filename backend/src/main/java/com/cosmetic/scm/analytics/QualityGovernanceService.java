package com.cosmetic.scm.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.complaint.ComplaintService;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.CapaStatus;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.domain.DeviationStatus;
import com.cosmetic.scm.domain.DocStatus;
import com.cosmetic.scm.domain.EquipmentStatus;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.mes.EquipmentService;
import com.cosmetic.scm.plm.DocControlService;
import com.cosmetic.scm.plm.FormulaVersion;
import com.cosmetic.scm.plm.FormulaVersionRepository;
import com.cosmetic.scm.plm.StabilityService;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.qms.SupplierAuditService;
import com.cosmetic.scm.recall.RecallService;
import com.cosmetic.scm.salesreturn.SalesReturn;
import com.cosmetic.scm.salesreturn.SalesReturnLine;
import com.cosmetic.scm.salesreturn.SalesReturnLineRepository;
import com.cosmetic.scm.salesreturn.SalesReturnRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 质量合规治理与库存 ABC 分析服务（V47）：
 * 1) 产品年度质量回顾报告（PQR）；
 * 2) 受控文件治理概览（已读确认、复审、纸质副本回收）；
 * 3) QMS 质量体系健康度汇总（偏差、CAPA、供应商审核、设备校准）；
 * 4) 库存 ABC 价值分类（按金额累计占比 70% / 90% / 100%，受价格权限保护）。
 */
@Service
@RequiredArgsConstructor
public class QualityGovernanceService {

    private final MfgOrderRepository mfgOrders;
    private final BatchRepository batches;
    private final DeviationService deviations;
    private final ComplaintService complaints;
    private final RecallService recalls;
    private final SalesReturnRepository salesReturns;
    private final SalesReturnLineRepository salesReturnLines;
    private final FormulaVersionRepository formulaVersions;
    private final StabilityService stability;
    private final DocControlService docControl;
    private final SupplierAuditService supplierAudits;
    private final EquipmentService equipment;
    private final InventoryService inventory;
    private final MasterService master;
    private final MasterLookup lookup;
    private final TimeProvider time;

    // ---------- 1. 产品年度质量回顾 (PQR) ----------

    public record PqrBatchRow(String moNo, String moTypeLabel, String factoryName, String outBatchNo,
                              BigDecimal plannedQty, BigDecimal actualQty, BigDecimal yieldPct,
                              LocalDateTime completedAt) {
    }

    public record PqrReport(Long fgItemId, String fgCode, String fgName, String filingNo,
                            LocalDate from, LocalDate to,
                            int batchCount, BigDecimal totalPlannedQty, BigDecimal totalActualQty, BigDecimal avgYieldPct,
                            int fgBatchTotal, int fgBatchReleased, int fgBatchRejected, BigDecimal fgPassRatePct,
                            int deviationCount, int openDeviations, int majorOrCriticalDeviations,
                            int complaintCount, int adverseCount, int recallCount,
                            int returnLineCount, BigDecimal returnedQty,
                            Integer effectiveVersionNo, int formulaChangesInPeriod,
                            Integer stabilitySupportedMonths, String stabilityState,
                            List<PqrBatchRow> batches) {
    }

    @Transactional(readOnly = true)
    public PqrReport pqr(Long fgItemId, LocalDate from, LocalDate to) {
        Item fg = lookup.item(fgItemId);
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品");
        }
        LocalDate today = time.today();
        LocalDate start = from != null ? from : today.minusDays(365);
        LocalDate end = to != null ? to : today;
        LocalDateTime startAt = start.atStartOfDay();
        LocalDateTime endBefore = end.plusDays(1).atStartOfDay();

        Map<Long, Partner> partners = lookup.partnerMap();
        List<MfgOrder> mos = mfgOrders.findAllByOrderByIdDesc().stream()
                .filter(m -> fgItemId.equals(m.getFgItemId()) && m.getStatus() == MoStatus.COMPLETED
                        && m.getCompletedAt() != null
                        && !m.getCompletedAt().isBefore(startAt) && m.getCompletedAt().isBefore(endBefore))
                .toList();
        List<Long> outIds = mos.stream().map(MfgOrder::getOutBatchId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> outBatchNos = outIds.isEmpty() ? Map.of()
                : batches.findAllById(outIds).stream().collect(Collectors.toMap(Batch::getId, Batch::getBatchNo));

        BigDecimal plannedSum = BigDecimal.ZERO;
        BigDecimal actualSum = BigDecimal.ZERO;
        List<PqrBatchRow> batchRows = new ArrayList<>();
        Set<String> moNos = new HashSet<>();
        Set<String> batchNoSet = new HashSet<>();
        for (MfgOrder m : mos) {
            moNos.add(m.getMoNo());
            String bno = m.getOutBatchId() == null ? "-" : outBatchNos.getOrDefault(m.getOutBatchId(), "-");
            if (!"-".equals(bno)) batchNoSet.add(bno);
            BigDecimal pl = m.getPlannedQty() == null ? BigDecimal.ZERO : m.getPlannedQty();
            BigDecimal ac = m.getActualQty() == null ? BigDecimal.ZERO : m.getActualQty();
            plannedSum = plannedSum.add(pl);
            actualSum = actualSum.add(ac);
            BigDecimal y = pl.signum() > 0 ? ac.multiply(BigDecimal.valueOf(100)).divide(pl, 1, RoundingMode.HALF_UP) : null;
            Partner f = partners.get(m.getFactoryId());
            batchRows.add(new PqrBatchRow(m.getMoNo(), m.getMoType().getLabel(), f == null ? "-" : f.getName(),
                    bno, pl, ac, y, m.getCompletedAt()));
        }
        BigDecimal avgYield = plannedSum.signum() > 0
                ? actualSum.multiply(BigDecimal.valueOf(100)).divide(plannedSum, 1, RoundingMode.HALF_UP) : null;

        int fgTotal = 0, fgRel = 0, fgRej = 0;
        for (Batch b : batches.findAll()) {
            if (!fgItemId.equals(b.getItemId()) || b.getCreatedAt() == null) continue;
            if (b.getCreatedAt().isBefore(startAt) || !b.getCreatedAt().isBefore(endBefore)) continue;
            fgTotal++;
            batchNoSet.add(b.getBatchNo());
            if (b.getStatus() == BatchStatus.RELEASED) fgRel++;
            else if (b.getStatus() == BatchStatus.REJECTED) fgRej++;
        }
        int judged = fgRel + fgRej;
        BigDecimal passRate = judged == 0 ? null : pct(fgRel, judged);

        int devCount = 0, devOpen = 0, devMajor = 0;
        for (var d : deviations.listAll()) {
            if (d.occurredDate() == null || d.occurredDate().isBefore(start) || d.occurredDate().isAfter(end)) continue;
            boolean match = (d.batchNo() != null && batchNoSet.contains(d.batchNo()))
                    || (d.moNo() != null && moNos.contains(d.moNo()));
            if (!match) continue;
            devCount++;
            if (d.status() == DeviationStatus.OPEN) devOpen++;
            if (d.level() != DeviationLevel.MINOR) devMajor++;
        }

        int compCount = 0, advCount = 0;
        for (var c : complaints.listAll()) {
            if (c.receivedDate() == null || c.receivedDate().isBefore(start) || c.receivedDate().isAfter(end)) continue;
            if (!fg.getName().equals(c.itemName())) continue;
            compCount++;
            if (c.reportDeadline() != null) advCount++;
        }

        int recCount = 0;
        for (var r : recalls.list()) {
            if (r.openedAt() == null || r.openedAt().isBefore(startAt) || !r.openedAt().isBefore(endBefore)) continue;
            if (fg.getName().equals(r.itemName())) recCount++;
        }

        List<SalesReturn> rets = salesReturns.findAll().stream()
                .filter(r -> r.getReturnDate() != null && !r.getReturnDate().isBefore(start) && !r.getReturnDate().isAfter(end))
                .toList();
        int retLines = 0;
        BigDecimal retQty = BigDecimal.ZERO;
        if (!rets.isEmpty()) {
            for (SalesReturnLine rl : salesReturnLines.findByReturnIdIn(rets.stream().map(SalesReturn::getId).toList())) {
                if (fgItemId.equals(rl.getItemId())) {
                    retLines++;
                    retQty = retQty.add(rl.getQty());
                }
            }
        }

        List<FormulaVersion> vers = formulaVersions.findByFgItemIdOrderByVersionNoDesc(fgItemId);
        Integer effVer = vers.stream().filter(v -> v.getStatus() == FormulaStatus.EFFECTIVE)
                .map(FormulaVersion::getVersionNo).findFirst().orElse(null);
        int verChanges = (int) vers.stream()
                .filter(v -> v.getApprovedAt() != null && !v.getApprovedAt().isBefore(startAt) && v.getApprovedAt().isBefore(endBefore))
                .count();
        var stab = stability.coverage().stream().filter(c -> fgItemId.equals(c.fgItemId())).findFirst().orElse(null);

        return new PqrReport(fg.getId(), fg.getCode(), fg.getName(), fg.getFilingNo(), start, end,
                batchRows.size(), plannedSum, actualSum, avgYield,
                fgTotal, fgRel, fgRej, passRate,
                devCount, devOpen, devMajor,
                compCount, advCount, recCount,
                retLines, retQty, effVer, verChanges,
                stab == null ? null : stab.supportedMonths(), stab == null ? "NONE" : stab.state(),
                batchRows);
    }

    @Transactional(readOnly = true)
    public List<PqrReport> pqrAll(LocalDate from, LocalDate to) {
        return lookup.itemMap().values().stream()
                .filter(i -> i.getItemType() == ItemType.FG && i.isActive())
                .sorted(Comparator.comparing(Item::getCode))
                .map(fg -> pqr(fg.getId(), from, to))
                .toList();
    }

    // ---------- 2. 受控文件治理概览 ----------

    public record DocsSummary(int activeDocs, int obsoleteDocs, int pendingApprovalCount,
                              int reviewDueCount, int reviewOverdueCount,
                              int ackPendingDocs, int ackPendingPeopleTotal, int ackOverdueDocs,
                              int copiesOutTotal, int copiesToRetrieveTotal, int copiesRetrieveOverdueDocs) {
    }

    @Transactional(readOnly = true)
    public DocsSummary docsSummary() {
        List<DocControlService.DocRow> rows = docControl.list();
        LocalDate warnLimit = time.today().plusDays(DocControlService.REVIEW_WARN_DAYS);
        int active = 0, obsolete = 0, pending = 0, revDue = 0, revOver = 0;
        int ackDocs = 0, ackPeople = 0, ackOver = 0, copiesOut = 0, copiesRet = 0, copiesOver = 0;
        for (var r : rows) {
            if (r.status() == DocStatus.ACTIVE) active++;
            else obsolete++;
            if (r.pendingRevisionId() != null) pending++;
            if (r.reviewDue() != null && !r.reviewDue().isAfter(warnLimit)) revDue++;
            if (r.reviewOverdue()) revOver++;
            if (r.ackPending() > 0) {
                ackDocs++;
                ackPeople += r.ackPending();
            }
            if (r.ackOverdue()) ackOver++;
            copiesOut += r.copiesOut();
            copiesRet += r.copiesToRetrieve();
            if (r.retrieveOverdue()) copiesOver++;
        }
        return new DocsSummary(active, obsolete, pending, revDue, revOver,
                ackDocs, ackPeople, ackOver, copiesOut, copiesRet, copiesOver);
    }

    // ---------- 3. QMS 质量体系健康度 ----------

    public record QmsSummary(int totalDeviations, int openDeviations, int overdueDeviations,
                             int closedDeviations, BigDecimal deviationCloseRatePct,
                             int totalCapas, int openCapas, int overdueCapas,
                             int verifiedCapas, BigDecimal capaVerifyRatePct,
                             int auditPartnersTotal, int qualifiedPartners, int auditOverdueCount, int auditDueCount,
                             int equipmentTotal, int equipmentInUse, int equipmentCalOverdue, int equipmentCalDue) {
    }

    @Transactional(readOnly = true)
    public QmsSummary qmsSummary() {
        var devs = deviations.listAll();
        int devTotal = devs.size();
        int devOpen = (int) devs.stream().filter(d -> d.status() == DeviationStatus.OPEN).count();
        int devOver = (int) devs.stream().filter(d -> d.status() == DeviationStatus.OPEN && d.overdue()).count();
        int devClosed = devTotal - devOpen;

        var capas = deviations.listAllCapas();
        int capaTotal = capas.size();
        int capaVer = (int) capas.stream().filter(c -> c.status() == CapaStatus.VERIFIED).count();
        int capaOpen = capaTotal - capaVer;
        int capaOver = (int) capas.stream().filter(c -> c.status() != CapaStatus.VERIFIED && c.overdue()).count();

        var partners = supplierAudits.partnerRows();
        int pTotal = partners.size();
        int pQual = (int) partners.stream().filter(SupplierAuditService.PartnerRow::qualified).count();
        int pOver = (int) partners.stream().filter(p -> "OVERDUE".equals(p.dueState())).count();
        int pDue = (int) partners.stream().filter(p -> "DUE".equals(p.dueState())).count();

        var equips = equipment.list();
        int eTotal = equips.size();
        int eInUse = (int) equips.stream().filter(e -> e.status() == EquipmentStatus.IN_USE).count();
        int eOver = (int) equips.stream().filter(e -> e.status() == EquipmentStatus.IN_USE && "OVERDUE".equals(e.dueState())).count();
        int eDue = (int) equips.stream().filter(e -> e.status() == EquipmentStatus.IN_USE && "DUE".equals(e.dueState())).count();

        return new QmsSummary(devTotal, devOpen, devOver, devClosed, devTotal == 0 ? null : pct(devClosed, devTotal),
                capaTotal, capaOpen, capaOver, capaVer, capaTotal == 0 ? null : pct(capaVer, capaTotal),
                pTotal, pQual, pOver, pDue, eTotal, eInUse, eOver, eDue);
    }

    // ---------- 4. 库存 ABC 价值分类 ----------

    public record AbcRow(Long itemId, String itemCode, String itemName, String itemType, String unit,
                         BigDecimal qty, BigDecimal unitCost, BigDecimal value,
                         BigDecimal sharePct, BigDecimal cumulativePct, String abcClass) {
    }

    public record AbcReport(LocalDate asOf, BigDecimal totalValue,
                            int aCount, BigDecimal aValue,
                            int bCount, BigDecimal bValue,
                            int cCount, BigDecimal cValue,
                            List<AbcRow> rows) {
    }

    @Transactional(readOnly = true)
    public AbcReport inventoryAbc() {
        Map<Long, BigDecimal> costs = master.unitCosts();
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, BigDecimal> qtyByItem = new LinkedHashMap<>();
        for (var s : inventory.listStock(null, null, null, false)) {
            if (s.qty() != null && s.qty().signum() > 0) {
                qtyByItem.merge(s.itemId(), s.qty(), BigDecimal::add);
            }
        }
        record Raw(Item item, BigDecimal qty, BigDecimal uc, BigDecimal val) {
        }
        List<Raw> raws = new ArrayList<>();
        BigDecimal totalVal = BigDecimal.ZERO;
        for (var e : qtyByItem.entrySet()) {
            Item item = items.get(e.getKey());
            BigDecimal uc = costs.get(e.getKey());
            if (uc == null || uc.signum() <= 0) continue;
            BigDecimal val = e.getValue().multiply(uc).setScale(2, RoundingMode.HALF_UP);
            raws.add(new Raw(item, e.getValue(), uc, val));
            totalVal = totalVal.add(val);
        }
        raws.sort(Comparator.comparing(Raw::val, Comparator.reverseOrder())
                .thenComparing(r -> r.item().getCode()));

        List<AbcRow> rows = new ArrayList<>();
        BigDecimal cum = BigDecimal.ZERO;
        int aCnt = 0, bCnt = 0, cCnt = 0;
        BigDecimal aVal = BigDecimal.ZERO, bVal = BigDecimal.ZERO, cVal = BigDecimal.ZERO;
        for (int idx = 0; idx < raws.size(); idx++) {
            Raw r = raws.get(idx);
            BigDecimal prevCumPct = totalVal.signum() == 0 ? BigDecimal.ZERO
                    : cum.multiply(BigDecimal.valueOf(100)).divide(totalVal, 1, RoundingMode.HALF_UP);
            cum = cum.add(r.val());
            BigDecimal share = totalVal.signum() == 0 ? BigDecimal.ZERO
                    : r.val().multiply(BigDecimal.valueOf(100)).divide(totalVal, 1, RoundingMode.HALF_UP);
            BigDecimal cumPct = totalVal.signum() == 0 ? BigDecimal.ZERO
                    : cum.multiply(BigDecimal.valueOf(100)).divide(totalVal, 1, RoundingMode.HALF_UP);
            String cls;
            if (idx == 0 || prevCumPct.compareTo(BigDecimal.valueOf(70)) < 0) {
                cls = "A";
                aCnt++;
                aVal = aVal.add(r.val());
            } else if (prevCumPct.compareTo(BigDecimal.valueOf(90)) < 0) {
                cls = "B";
                bCnt++;
                bVal = bVal.add(r.val());
            } else {
                cls = "C";
                cCnt++;
                cVal = cVal.add(r.val());
            }
            rows.add(new AbcRow(r.item().getId(), r.item().getCode(), r.item().getName(),
                    r.item().getItemType().name(), r.item().getUnit(),
                    r.qty(), r.uc(), r.val(), share, cumPct, cls));
        }
        return new AbcReport(time.today(), totalVal, aCnt, aVal, bCnt, bVal, cCnt, cVal, rows);
    }

    private static BigDecimal pct(int num, int den) {
        return BigDecimal.valueOf(num).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(den), 1, RoundingMode.HALF_UP);
    }
}
