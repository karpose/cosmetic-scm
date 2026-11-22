package com.cosmetic.scm.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ReturnDisposition;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.InventoryService.StockRow;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.salesreturn.ReturnService;
import com.cosmetic.scm.salesreturn.ReturnService.LineView;
import com.cosmetic.scm.salesreturn.ReturnService.ReturnView;
import com.cosmetic.scm.security.PriceAccess;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * V49 库存效期风险看板与销售退货处置复盘分析。
 * 效期风险货值严格按 PriceAccess 隔离：无价格权限的岗位 unitCost 与 value 均返回 null。
 */
@Service
@RequiredArgsConstructor
public class ExpiryRiskAndReturnsService {

    private final InventoryService inventory;
    private final MasterService master;
    private final ReturnService returns;
    private final TimeProvider time;

    // ---------- 库存效期风险 ----------

    /** riskLevel: EXPIRED / CRITICAL(<1/3) / NEAR(1/3~50%) / DUE_90D / HEALTHY */
    public record ExpiryRiskRow(Long batchId, String batchNo, String itemCode, String itemName, String itemType,
                                Long warehouseId, String warehouseName, BigDecimal qty, String unit, String statusLabel,
                                LocalDate mfgDate, LocalDate expDate, Long daysLeft, Double shelfRatio,
                                String riskLevel, String riskLabel, BigDecimal unitCost, BigDecimal value,
                                String recommendedAction, String actionRoute) {
    }

    public record ExpiryRiskReport(LocalDate asOf, boolean canSeeValue,
                                   long totalBatches,
                                   long expiredCount, BigDecimal expiredValue,
                                   long criticalCount, BigDecimal criticalValue,
                                   long nearCount, BigDecimal nearValue,
                                   long due90dCount, BigDecimal due90dValue,
                                   BigDecimal totalRiskValue, long expiredUnfrozenCount,
                                   List<ExpiryRiskRow> riskRows) {
    }

    @Transactional(readOnly = true)
    public ExpiryRiskReport expiryRisk() {
        LocalDate today = time.today();
        boolean canSeePrice = PriceAccess.allowed();
        Map<Long, BigDecimal> costs = canSeePrice ? master.unitCosts() : Map.of();
        List<StockRow> stock = inventory.listStock(null, null, null, false);

        long expiredCount = 0;
        long expiredUnfrozenCount = 0;
        BigDecimal expiredVal = BigDecimal.ZERO;
        long criticalCount = 0;
        BigDecimal criticalVal = BigDecimal.ZERO;
        long nearCount = 0;
        BigDecimal nearVal = BigDecimal.ZERO;
        long due90Count = 0;
        BigDecimal due90Val = BigDecimal.ZERO;

        List<ExpiryRiskRow> riskRows = new ArrayList<>();
        for (StockRow s : stock) {
            if (s.qty() == null || s.qty().signum() <= 0) {
                continue;
            }
            Long daysLeft = s.expDate() == null ? null : ChronoUnit.DAYS.between(today, s.expDate());
            Double ratio = s.shelfRatio();
            String level;
            String label;
            if (daysLeft != null && daysLeft < 0) {
                level = "EXPIRED";
                label = "已过期";
            } else if (ratio != null && ratio < (1.0 / 3.0)) {
                level = "CRITICAL";
                label = "高危(<1/3)";
            } else if (ratio != null && ratio < 0.5) {
                level = "NEAR";
                label = "临期(1/3~50%)";
            } else if (daysLeft != null && daysLeft <= 90) {
                level = "DUE_90D";
                label = "90天内到期";
            } else {
                level = "HEALTHY";
                label = "正常";
            }

            BigDecimal cost = canSeePrice ? costs.get(s.itemId()) : null;
            BigDecimal val = (canSeePrice && cost != null)
                    ? cost.multiply(s.qty()).setScale(2, RoundingMode.HALF_UP)
                    : null;

            switch (level) {
                case "EXPIRED" -> {
                    expiredCount++;
                    if (s.status() != com.cosmetic.scm.domain.BatchStatus.FROZEN
                            && s.status() != com.cosmetic.scm.domain.BatchStatus.REJECTED) {
                        expiredUnfrozenCount++;
                    }
                    if (val != null) expiredVal = expiredVal.add(val);
                }
                case "CRITICAL" -> {
                    criticalCount++;
                    if (val != null) criticalVal = criticalVal.add(val);
                }
                case "NEAR" -> {
                    nearCount++;
                    if (val != null) nearVal = nearVal.add(val);
                }
                case "DUE_90D" -> {
                    due90Count++;
                    if (val != null) due90Val = due90Val.add(val);
                }
                default -> { }
            }

            if (!"HEALTHY".equals(level)) {
                boolean isFg = "FG".equals(s.itemType());
                String action;
                String route;
                switch (level) {
                    case "EXPIRED" -> {
                        action = "立即冻结或报废清零";
                        route = "/inventory-ops";
                    }
                    case "CRITICAL" -> {
                        action = isFg ? "低效期渠道促销出清或报废" : "优先排产消耗或报废评估";
                        route = isFg ? "/sales" : "/production";
                    }
                    case "NEAR" -> {
                        action = isFg ? "优先发往效期宽松渠道" : "优先安排生产领用";
                        route = isFg ? "/sales" : "/production";
                    }
                    default -> {
                        action = "纳入效期跟踪与消耗计划";
                        route = "/planning";
                    }
                }
                riskRows.add(new ExpiryRiskRow(s.batchId(), s.batchNo(), s.itemCode(), s.itemName(), s.itemType(),
                        s.warehouseId(), s.warehouseName(), s.qty(), s.unit(), s.statusLabel(), s.mfgDate(), s.expDate(),
                        daysLeft, ratio, level, label, cost, val, action, route));
            }
        }
        riskRows.sort(Comparator.comparing((ExpiryRiskRow r) -> riskOrder(r.riskLevel()))
                .thenComparing(r -> r.daysLeft() == null ? Long.MAX_VALUE : r.daysLeft()));

        BigDecimal totalRiskVal = expiredVal.add(criticalVal).add(nearVal).add(due90Val);
        return new ExpiryRiskReport(today, canSeePrice, stock.size(),
                expiredCount, canSeePrice ? expiredVal : null,
                criticalCount, canSeePrice ? criticalVal : null,
                nearCount, canSeePrice ? nearVal : null,
                due90Count, canSeePrice ? due90Val : null,
                canSeePrice ? totalRiskVal : null, expiredUnfrozenCount,
                riskRows);
    }

    private static int riskOrder(String level) {
        return switch (level) {
            case "EXPIRED" -> 0;
            case "CRITICAL" -> 1;
            case "NEAR" -> 2;
            case "DUE_90D" -> 3;
            default -> 4;
        };
    }

    // ---------- 销售退货处置复盘 ----------

    public record ReturnRankRow(String name, long returnCount, BigDecimal totalQty, BigDecimal restockQty,
                                BigDecimal scrapQty, BigDecimal pendingQty, Double restockRatePct) {
    }

    public record SalesReturnReport(LocalDate from, LocalDate to,
                                    long totalReturns, long recallReturns,
                                    long totalLines, long pendingLines,
                                    BigDecimal totalQty, BigDecimal restockQty, BigDecimal scrapQty, BigDecimal pendingQty,
                                    Double restockRatePct, Double scrapRatePct,
                                    List<ReturnRankRow> byCustomer, List<ReturnRankRow> byItem) {
    }

    @Transactional(readOnly = true)
    public SalesReturnReport salesReturnReport(LocalDate from, LocalDate to) {
        LocalDate today = time.today();
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(364);

        List<ReturnView> list = returns.listAll().stream()
                .filter(r -> r.returnDate() != null
                        && !r.returnDate().isBefore(start)
                        && !r.returnDate().isAfter(end))
                .toList();

        long totalReturns = list.size();
        long recallReturns = list.stream().filter(r -> r.recallId() != null).count();
        long totalLines = 0;
        long pendingLines = 0;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal restockQty = BigDecimal.ZERO;
        BigDecimal scrapQty = BigDecimal.ZERO;
        BigDecimal pendingQty = BigDecimal.ZERO;

        Map<String, Acc> byCustomer = new LinkedHashMap<>();
        Map<String, Acc> byItem = new LinkedHashMap<>();

        for (ReturnView r : list) {
            Acc custAcc = byCustomer.computeIfAbsent(r.customerName(), k -> new Acc());
            custAcc.returnCount++;
            java.util.Set<String> seenItems = new java.util.HashSet<>();
            for (LineView l : r.lines()) {
                totalLines++;
                BigDecimal q = l.qty() == null ? BigDecimal.ZERO : l.qty();
                totalQty = totalQty.add(q);
                Acc itemAcc = byItem.computeIfAbsent(l.itemName(), k -> new Acc());
                if (seenItems.add(l.itemName())) {
                    itemAcc.returnCount++;
                }
                custAcc.totalQty = custAcc.totalQty.add(q);
                itemAcc.totalQty = itemAcc.totalQty.add(q);
                if (l.disposition() == ReturnDisposition.RESTOCK) {
                    restockQty = restockQty.add(q);
                    custAcc.restockQty = custAcc.restockQty.add(q);
                    itemAcc.restockQty = itemAcc.restockQty.add(q);
                } else if (l.disposition() == ReturnDisposition.SCRAP) {
                    scrapQty = scrapQty.add(q);
                    custAcc.scrapQty = custAcc.scrapQty.add(q);
                    itemAcc.scrapQty = itemAcc.scrapQty.add(q);
                } else {
                    pendingLines++;
                    pendingQty = pendingQty.add(q);
                    custAcc.pendingQty = custAcc.pendingQty.add(q);
                    itemAcc.pendingQty = itemAcc.pendingQty.add(q);
                }
            }
        }

        BigDecimal disposedQty = restockQty.add(scrapQty);
        Double restockRate = pct(restockQty, disposedQty);
        Double scrapRate = pct(scrapQty, disposedQty);

        return new SalesReturnReport(start, end, totalReturns, recallReturns, totalLines, pendingLines,
                totalQty, restockQty, scrapQty, pendingQty, restockRate, scrapRate,
                toRanks(byCustomer), toRanks(byItem));
    }

    private static class Acc {
        long returnCount;
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal restockQty = BigDecimal.ZERO;
        BigDecimal scrapQty = BigDecimal.ZERO;
        BigDecimal pendingQty = BigDecimal.ZERO;
    }

    private static List<ReturnRankRow> toRanks(Map<String, Acc> map) {
        return map.entrySet().stream()
                .map(e -> {
                    Acc a = e.getValue();
                    BigDecimal disposed = a.restockQty.add(a.scrapQty);
                    return new ReturnRankRow(e.getKey(), a.returnCount, a.totalQty, a.restockQty, a.scrapQty,
                            a.pendingQty, pct(a.restockQty, disposed));
                })
                .sorted(Comparator.comparing(ReturnRankRow::totalQty).reversed().thenComparing(ReturnRankRow::name))
                .toList();
    }

    private static Double pct(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() <= 0) {
            return null;
        }
        return num.multiply(BigDecimal.valueOf(100)).divide(den, 1, RoundingMode.HALF_UP).doubleValue();
    }
}
