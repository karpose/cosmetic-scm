package com.cosmetic.scm.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.SupplierReturnStatus;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.purchase.PoShipment;
import com.cosmetic.scm.purchase.PoShipmentRepository;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.sourcing.SupplierPriceRepository;
import com.cosmetic.scm.supplierreturn.SupplierReturn;
import com.cosmetic.scm.supplierreturn.SupplierReturnRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 供应商绩效考核台账：按时间区间汇总来料合格率、准时交货率、收货异议次数、退供次数与有效协议价覆盖数，
 * 给出综合评分与 A/B/C/D 等级。不返回任何价格或金额，可供质量与采购共同用于供应商季度复审。
 */
@Service
@RequiredArgsConstructor
public class SupplierPerformanceService {

    private final MasterLookup lookup;
    private final BatchRepository batches;
    private final PurchaseOrderRepository purchaseOrders;
    private final PoShipmentRepository shipments;
    private final SupplierReturnRepository returns;
    private final SupplierPriceRepository supplierPrices;
    private final TimeProvider time;

    public record SupplierPerfRow(Long supplierId, String supplierCode, String supplierName, boolean qualified,
                                  int totalBatches, int releasedBatches, int rejectedBatches, BigDecimal passRatePct,
                                  int deliveredPoCount, int onTimePoCount, BigDecimal onTimeRatePct,
                                  int receiptDisputeCount, int returnCount, int activeContractCount,
                                  BigDecimal score, String grade) {
    }

    @Transactional(readOnly = true)
    public List<SupplierPerfRow> report(LocalDate from, LocalDate to) {
        LocalDate today = time.today();
        LocalDate start = from != null ? from : today.minusDays(180);
        LocalDate end = to != null ? to : today;
        LocalDateTime startAt = start.atStartOfDay();
        LocalDateTime endBefore = end.plusDays(1).atStartOfDay();

        Map<Long, int[]> batchStats = new HashMap<>();
        for (Batch b : batches.findAll()) {
            if (b.getSourceType() != SourceType.PO || b.getPartnerId() == null || b.getCreatedAt() == null) {
                continue;
            }
            if (b.getCreatedAt().isBefore(startAt) || !b.getCreatedAt().isBefore(endBefore)) {
                continue;
            }
            int[] st = batchStats.computeIfAbsent(b.getPartnerId(), k -> new int[3]);
            st[0]++;
            if (b.getStatus() == BatchStatus.RELEASED) {
                st[1]++;
            } else if (b.getStatus() == BatchStatus.REJECTED) {
                st[2]++;
            }
        }

        Map<Long, Long> poToSupplier = new HashMap<>();
        Map<Long, int[]> poStats = new HashMap<>();
        for (PurchaseOrder po : purchaseOrders.findAllByOrderByIdDesc()) {
            poToSupplier.put(po.getId(), po.getSupplierId());
            if (po.getOrderDate() == null || po.getOrderDate().isBefore(start) || po.getOrderDate().isAfter(end)) {
                continue;
            }
            LocalDate due = po.getPromisedDate() != null ? po.getPromisedDate() : po.getExpectedDate();
            if (po.getLastReceivedOn() != null && due != null) {
                int[] st = poStats.computeIfAbsent(po.getSupplierId(), k -> new int[2]);
                st[0]++;
                if (!po.getLastReceivedOn().isAfter(due)) {
                    st[1]++;
                }
            }
        }

        Map<Long, Integer> disputes = new HashMap<>();
        for (PoShipment s : shipments.findAll()) {
            if (s.getReceiptDisputedAt() == null) {
                continue;
            }
            if (s.getReceiptDisputedAt().isBefore(startAt) || !s.getReceiptDisputedAt().isBefore(endBefore)) {
                continue;
            }
            Long supId = poToSupplier.get(s.getPoId());
            if (supId != null) {
                disputes.merge(supId, 1, Integer::sum);
            }
        }

        Map<Long, Integer> returnCounts = new HashMap<>();
        for (SupplierReturn r : returns.findAll()) {
            if (r.getStatus() != SupplierReturnStatus.DONE || r.getSignedAt() == null) {
                continue;
            }
            if (r.getSignedAt().isBefore(startAt) || !r.getSignedAt().isBefore(endBefore)) {
                continue;
            }
            returnCounts.merge(r.getSupplierId(), 1, Integer::sum);
        }

        Map<Long, Integer> contractCounts = new HashMap<>();
        supplierPrices.findAllByOrderByIdDesc().stream()
                .filter(p -> p.isEffectiveOn(today))
                .forEach(p -> contractCounts.merge(p.getSupplierId(), 1, Integer::sum));

        List<SupplierPerfRow> rows = new ArrayList<>();
        for (Partner p : lookup.partnerMap().values()) {
            if (p.getKind() != PartnerKind.SUPPLIER) {
                continue;
            }
            int[] bs = batchStats.getOrDefault(p.getId(), new int[3]);
            int[] ps = poStats.getOrDefault(p.getId(), new int[2]);
            int disp = disputes.getOrDefault(p.getId(), 0);
            int rets = returnCounts.getOrDefault(p.getId(), 0);
            int contracts = contractCounts.getOrDefault(p.getId(), 0);

            int judged = bs[1] + bs[2];
            BigDecimal passRate = judged == 0 ? null : pct(bs[1], judged);
            BigDecimal onTimeRate = ps[0] == 0 ? null : pct(ps[1], ps[0]);

            BigDecimal collabScore = BigDecimal.valueOf(Math.max(0, 100 - disp * 10 - rets * 15));
            BigDecimal weightSum = BigDecimal.ZERO;
            BigDecimal weighted = BigDecimal.ZERO;
            if (passRate != null) {
                weighted = weighted.add(passRate.multiply(BigDecimal.valueOf(50)));
                weightSum = weightSum.add(BigDecimal.valueOf(50));
            }
            if (onTimeRate != null) {
                weighted = weighted.add(onTimeRate.multiply(BigDecimal.valueOf(30)));
                weightSum = weightSum.add(BigDecimal.valueOf(30));
            }
            if (bs[0] > 0 || ps[0] > 0 || disp > 0 || rets > 0) {
                weighted = weighted.add(collabScore.multiply(BigDecimal.valueOf(20)));
                weightSum = weightSum.add(BigDecimal.valueOf(20));
            }
            BigDecimal score = weightSum.signum() == 0 ? null : weighted.divide(weightSum, 1, RoundingMode.HALF_UP);
            String grade = score == null ? "-" : gradeOf(score);
            rows.add(new SupplierPerfRow(p.getId(), p.getCode(), p.getName(), p.isQualified(),
                    bs[0], bs[1], bs[2], passRate, ps[0], ps[1], onTimeRate,
                    disp, rets, contracts, score, grade));
        }
        rows.sort(Comparator.comparing(SupplierPerfRow::score, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SupplierPerfRow::supplierCode));
        return rows;
    }

    private static BigDecimal pct(int num, int den) {
        return BigDecimal.valueOf(num).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(den), 1, RoundingMode.HALF_UP);
    }

    private static String gradeOf(BigDecimal score) {
        if (score.compareTo(BigDecimal.valueOf(90)) >= 0) return "A";
        if (score.compareTo(BigDecimal.valueOf(75)) >= 0) return "B";
        if (score.compareTo(BigDecimal.valueOf(60)) >= 0) return "C";
        return "D";
    }
}
