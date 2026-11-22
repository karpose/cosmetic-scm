package com.cosmetic.scm.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.OemReportStatus;
import com.cosmetic.scm.production.ProductionService;
import com.cosmetic.scm.production.ProductionService.MoView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V51 生产收率与自产/OEM 工厂效率对比看板。
 * 统计完工批数、平均收率、在制工单延期预警、受托厂完工申报待确认积压，以及按工厂和成品的效率对比。
 */
@Service
@RequiredArgsConstructor
public class ProductionEfficiencyService {

    private final ProductionService production;
    private final TimeProvider time;

    public record FactoryEfficiencyRow(String factoryName, String moTypeLabel, long completedCount, long wipCount,
                                       long overdueCount, BigDecimal totalPlannedQty, BigDecimal totalActualQty,
                                       Double avgYieldPct) {
    }

    public record ItemEfficiencyRow(String fgCode, String fgName, long completedCount,
                                    BigDecimal totalPlannedQty, BigDecimal totalActualQty, Double avgYieldPct) {
    }

    public record ProductionEfficiencyReport(LocalDate asOf, long totalOrders, long completedOrders, long wipOrders,
                                             long overdueWipOrders, long pendingOemReports, Double overallAvgYieldPct,
                                             List<FactoryEfficiencyRow> byFactory, List<ItemEfficiencyRow> byItem) {
    }

    @Transactional(readOnly = true)
    public ProductionEfficiencyReport report() {
        LocalDate today = time.today();
        List<MoView> all = production.listAll().stream()
                .filter(m -> m.status() != MoStatus.CANCELLED)
                .toList();

        long completed = 0;
        long wip = 0;
        long overdue = 0;
        long pendingOem = 0;
        BigDecimal totalYieldSum = BigDecimal.ZERO;
        long totalYieldCnt = 0;

        Map<String, FacAcc> byFac = new LinkedHashMap<>();
        Map<String, ItemAcc> byItem = new LinkedHashMap<>();

        for (MoView m : all) {
            FacAcc fa = byFac.computeIfAbsent(m.factoryName() + "|" + m.moTypeLabel(),
                    k -> new FacAcc(m.factoryName(), m.moTypeLabel()));
            if (m.status() == MoStatus.COMPLETED) {
                completed++;
                fa.completed++;
                BigDecimal planned = m.plannedQty() == null ? BigDecimal.ZERO : m.plannedQty();
                BigDecimal actual = m.actualQty() == null ? BigDecimal.ZERO : m.actualQty();
                fa.planned = fa.planned.add(planned);
                fa.actual = fa.actual.add(actual);
                ItemAcc ia = byItem.computeIfAbsent(m.fgCode(), k -> new ItemAcc(m.fgCode(), m.fgName()));
                ia.completed++;
                ia.planned = ia.planned.add(planned);
                ia.actual = ia.actual.add(actual);
                if (m.yieldPct() != null) {
                    totalYieldSum = totalYieldSum.add(m.yieldPct());
                    totalYieldCnt++;
                    fa.yieldSum = fa.yieldSum.add(m.yieldPct());
                    fa.yieldCnt++;
                    ia.yieldSum = ia.yieldSum.add(m.yieldPct());
                    ia.yieldCnt++;
                }
            } else {
                wip++;
                fa.wip++;
                if (m.plannedDate() != null && m.plannedDate().isBefore(today)) {
                    overdue++;
                    fa.overdue++;
                }
                if (m.oemReportStatus() == OemReportStatus.SUBMITTED) {
                    pendingOem++;
                }
            }
        }

        List<FactoryEfficiencyRow> facRows = byFac.values().stream()
                .map(a -> new FactoryEfficiencyRow(a.name, a.typeLabel, a.completed, a.wip, a.overdue,
                        a.planned, a.actual, avg(a.yieldSum, a.yieldCnt)))
                .sorted(Comparator.comparingLong(FactoryEfficiencyRow::completedCount).reversed()
                        .thenComparing(FactoryEfficiencyRow::factoryName))
                .toList();

        List<ItemEfficiencyRow> itemRows = byItem.values().stream()
                .map(a -> new ItemEfficiencyRow(a.code, a.name, a.completed, a.planned, a.actual,
                        avg(a.yieldSum, a.yieldCnt)))
                .sorted(Comparator.comparingLong(ItemEfficiencyRow::completedCount).reversed()
                        .thenComparing(ItemEfficiencyRow::fgCode))
                .toList();

        return new ProductionEfficiencyReport(today, all.size(), completed, wip, overdue, pendingOem,
                avg(totalYieldSum, totalYieldCnt), facRows, itemRows);
    }

    private static class FacAcc {
        final String name;
        final String typeLabel;
        long completed;
        long wip;
        long overdue;
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        BigDecimal yieldSum = BigDecimal.ZERO;
        long yieldCnt;

        FacAcc(String name, String typeLabel) {
            this.name = name;
            this.typeLabel = typeLabel;
        }
    }

    private static class ItemAcc {
        final String code;
        final String name;
        long completed;
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal actual = BigDecimal.ZERO;
        BigDecimal yieldSum = BigDecimal.ZERO;
        long yieldCnt;

        ItemAcc(String code, String name) {
            this.code = code;
            this.name = name;
        }
    }

    private static Double avg(BigDecimal sum, long count) {
        return count <= 0 ? null : sum.divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP).doubleValue();
    }
}
