package com.cosmetic.scm.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.complaint.Complaint;
import com.cosmetic.scm.complaint.ComplaintRepository;
import com.cosmetic.scm.complaint.ComplaintService;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ComplaintCategory;
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.QcStage;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.Stock;
import com.cosmetic.scm.inventory.StockRepository;
import com.cosmetic.scm.inventory.StockTxn;
import com.cosmetic.scm.inventory.StockTxnRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.planning.PlanningService;
import com.cosmetic.scm.planning.PlanningService.FgRow;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.quality.QualityDecision;
import com.cosmetic.scm.quality.QualityDecisionRepository;
import com.cosmetic.scm.trace.TraceService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 经营看板：库存健康（效期分布、可售天数、呆滞）、受托厂交付与收率、放行周期、投诉、追溯响应时间。
 * 系统里还没有成本和售价，所以全部按数量统计；接入财务成本后可在这里换算成金额。
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    /** 完工晚于计划日期不超过这么多天算准时。 */
    public static final int ON_TIME_GRACE_DAYS = 3;
    /** 超过这么多天没有任何出库类流水、且已生产超过这么多天的在库批次算呆滞。 */
    public static final int SLOW_MOVING_DAYS = 60;
    /** 一期验收门槛：任一批号 2 小时内出具完整正反向追溯链。 */
    public static final long TRACE_TARGET_MS = Duration.ofHours(2).toMillis();
    private static final int LOOKBACK_DAYS = 180;

    private final StockRepository stocks;
    private final BatchRepository batches;
    private final StockTxnRepository txns;
    private final MfgOrderRepository mfgOrders;
    private final QualityDecisionRepository decisions;
    private final ComplaintRepository complaints;
    private final PlanningService planning;
    private final TraceService trace;
    private final MasterLookup lookup;
    private final TimeProvider time;

    public record Headline(BigDecimal fgOnHand, Double nearExpiryShare, Integer medianDaysOfSupply, Double onTimeRate,
                           Double avgReleaseDays, Double complaintsPer10k, long traceAvgMs) {
    }

    /** 成品按剩余效期分档：<1/3、1/3–1/2、1/2–2/3、≥2/3。 */
    public record ShelfBuckets(String itemName, BigDecimal lt33, BigDecimal lt50, BigDecimal lt67, BigDecimal ge67) {
    }

    public record SupplyRow(String itemName, BigDecimal available, BigDecimal dailyDemand, Integer daysOfSupply,
                            BigDecimal turnsPerYear) {
    }

    public record SlowRow(String batchNo, String itemName, String itemType, BigDecimal qty, String unit, LocalDate mfgDate,
                          Double shelfRatio) {
    }

    public record FactoryRow(String factoryName, String kindLabel, int completed, int onTime, Double onTimeRate,
                             Double avgYieldPct, Double avgLeadDays) {
    }

    public record QualityCycle(int materialDecisions, Double avgMaterialQcDays, int releaseDecisions,
                               Double avgReleaseDays, Double materialPassRate) {
    }

    public record MonthCount(String month, long total, long adverse) {
    }

    public record ComplaintStats(List<MonthCount> monthly, long open, long adverseOverdue, Double per10kShipped,
                                 Double adverseReportOnTimeRate) {
    }

    public record TraceStats(int sampled, long avgMs, long maxMs, long targetMs) {
    }

    public record Kpi(LocalDateTime generatedAt, Headline headline, List<ShelfBuckets> shelf, List<SupplyRow> supply,
                      List<SlowRow> slowMoving, List<FactoryRow> factories, QualityCycle quality,
                      ComplaintStats complaints, TraceStats trace) {
    }

    @Transactional(readOnly = true)
    public Kpi kpi() {
        LocalDate today = time.today();
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        List<Stock> rows = stocks.findByQtyGreaterThan(BigDecimal.ZERO);
        Map<Long, Batch> bm = batches.findAllById(rows.stream().map(Stock::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));

        // ---------- 成品效期分布 ----------
        Map<Long, BigDecimal[]> buckets = new LinkedHashMap<>();
        BigDecimal fgTotal = BigDecimal.ZERO;
        BigDecimal fgNear = BigDecimal.ZERO;
        for (Stock s : rows) {
            Batch b = bm.get(s.getBatchId());
            Item i = im.get(s.getItemId());
            if (i.getItemType() != ItemType.FG || b.getStatus() != BatchStatus.RELEASED
                    || wm.get(s.getWarehouseId()).getKind() != WarehouseKind.OWN) {
                continue;
            }
            Double r = ShelfLife.remainingRatio(b.getMfgDate(), b.getExpDate(), today);
            int idx = r == null ? 3 : r < 1.0 / 3 ? 0 : r < 0.5 ? 1 : r < 2.0 / 3 ? 2 : 3;
            buckets.computeIfAbsent(i.getId(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            buckets.get(i.getId())[idx] = buckets.get(i.getId())[idx].add(s.getQty());
            fgTotal = fgTotal.add(s.getQty());
            if (idx <= 1) {
                fgNear = fgNear.add(s.getQty());
            }
        }
        List<ShelfBuckets> shelf = buckets.entrySet().stream().map(e -> new ShelfBuckets(im.get(e.getKey()).getName(),
                e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3])).toList();

        // ---------- 可售天数与周转（沿用补货建议的默认口径：近 90 天出库） ----------
        List<FgRow> plan = planning.finishedGoods(new PlanningService.PlanParams(90, 30, 15, 60));
        List<SupplyRow> supply = plan.stream().map(p -> new SupplyRow(p.name(), p.available(), p.dailyDemand(),
                p.dailyDemand().signum() == 0 ? null : p.available().divide(p.dailyDemand(), 0, RoundingMode.DOWN).intValue(),
                p.available().signum() == 0 ? null : p.dailyDemand().multiply(BigDecimal.valueOf(365))
                        .divide(p.available(), 1, RoundingMode.HALF_UP))).toList();
        List<Integer> dos = supply.stream().map(SupplyRow::daysOfSupply).filter(Objects::nonNull).sorted().toList();
        Integer medianDos = dos.isEmpty() ? null : dos.get(dos.size() / 2);

        // ---------- 呆滞：已生产超过 60 天、近 60 天没有任何出库类流水的在库批次 ----------
        LocalDateTime since = today.minusDays(SLOW_MOVING_DAYS).atStartOfDay();
        Set<Long> moved = txns.findByCreatedAtGreaterThanEqualAndQtyDeltaLessThan(since, BigDecimal.ZERO).stream()
                .map(StockTxn::getBatchId).collect(Collectors.toSet());
        Map<Long, BigDecimal> qtyByBatch = new HashMap<>();
        for (Stock s : rows) {
            if (wm.get(s.getWarehouseId()).getKind() != WarehouseKind.RETURN) {
                qtyByBatch.merge(s.getBatchId(), s.getQty(), BigDecimal::add);
            }
        }
        List<SlowRow> slow = qtyByBatch.entrySet().stream()
                .map(e -> bm.get(e.getKey()))
                .filter(b -> b.getStatus() == BatchStatus.RELEASED && !moved.contains(b.getId())
                        && b.getMfgDate() != null && b.getMfgDate().isBefore(today.minusDays(SLOW_MOVING_DAYS)))
                .map(b -> {
                    Item i = im.get(b.getItemId());
                    return new SlowRow(b.getBatchNo(), i.getName(), i.getItemType().getLabel(), qtyByBatch.get(b.getId()),
                            i.getUnit(), b.getMfgDate(), ShelfLife.remainingRatio(b.getMfgDate(), b.getExpDate(), today));
                })
                .sorted(Comparator.comparing(SlowRow::mfgDate))
                .limit(10).toList();

        // ---------- 受托厂 / 自有工厂交付与收率（近 180 天完工） ----------
        LocalDateTime from = today.minusDays(LOOKBACK_DAYS).atStartOfDay();
        List<MfgOrder> done = mfgOrders.findByStatusIn(List.of(MoStatus.COMPLETED)).stream()
                .filter(m -> m.getCompletedAt() != null && !m.getCompletedAt().isBefore(from)).toList();
        Map<Long, LocalDate> outMfg = batches.findAllById(done.stream().map(MfgOrder::getOutBatchId).filter(Objects::nonNull).toList())
                .stream().filter(b -> b.getMfgDate() != null).collect(Collectors.toMap(Batch::getId, Batch::getMfgDate));
        Map<Long, Partner> pm = lookup.partnerMap();
        List<FactoryRow> factories = new ArrayList<>();
        int allDone = 0;
        int allOnTime = 0;
        for (Map.Entry<Long, List<MfgOrder>> e : done.stream().collect(Collectors.groupingBy(MfgOrder::getFactoryId)).entrySet()) {
            int onTime = 0;
            int judged = 0;
            double yieldSum = 0;
            double leadSum = 0;
            for (MfgOrder m : e.getValue()) {
                LocalDate actual = outMfg.getOrDefault(m.getOutBatchId(), m.getCompletedAt().toLocalDate());
                if (m.getPlannedDate() != null) {
                    judged++;
                    if (!actual.isAfter(m.getPlannedDate().plusDays(ON_TIME_GRACE_DAYS))) {
                        onTime++;
                    }
                }
                yieldSum += m.getActualQty().doubleValue() / m.getPlannedQty().doubleValue() * 100;
                leadSum += ChronoUnit.HOURS.between(m.getCreatedAt(), m.getCompletedAt()) / 24.0;
            }
            int n = e.getValue().size();
            Partner f = pm.get(e.getKey());
            factories.add(new FactoryRow(f.getName(), f.getKind().getLabel(), n, onTime,
                    judged == 0 ? null : round1(onTime * 100.0 / judged), round1(yieldSum / n), round1(leadSum / n)));
            allDone += judged;
            allOnTime += onTime;
        }

        // ---------- 放行周期 ----------
        List<QualityDecision> ds = decisions.findBySignedAtGreaterThanEqual(from);
        Map<Long, Batch> dbm = batches.findAllById(ds.stream().map(QualityDecision::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        List<Double> mq = new ArrayList<>();
        List<Double> rel = new ArrayList<>();
        int mqPass = 0;
        for (QualityDecision d : ds) {
            double days = ChronoUnit.MINUTES.between(dbm.get(d.getBatchId()).getCreatedAt(), d.getSignedAt()) / 1440.0;
            if (d.getStage() == QcStage.MATERIAL_QC) {
                mq.add(days);
                if (d.getDecision() == Decision.RELEASED) {
                    mqPass++;
                }
            } else {
                rel.add(days);
            }
        }
        QualityCycle quality = new QualityCycle(mq.size(), avg(mq), rel.size(), avg(rel),
                mq.isEmpty() ? null : round1(mqPass * 100.0 / mq.size()));

        // ---------- 投诉 ----------
        YearMonth thisMonth = YearMonth.from(today);
        List<Complaint> cs = complaints.findByReceivedDateGreaterThanEqual(thisMonth.minusMonths(5).atDay(1));
        List<MonthCount> monthly = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            YearMonth ym = thisMonth.minusMonths(i);
            List<Complaint> in = cs.stream().filter(c -> YearMonth.from(c.getReceivedDate()).equals(ym)).toList();
            monthly.add(new MonthCount(ym.toString(), in.size(), in.stream().filter(c -> c.getCategory() == ComplaintCategory.ADVERSE).count()));
        }
        long open = cs.stream().filter(c -> c.getClosedAt() == null).count();
        long overdue = cs.stream().filter(c -> c.getCategory() == ComplaintCategory.ADVERSE && c.getReportedDate() == null
                && ComplaintService.reportDeadline(c).isBefore(today)).count();
        List<Complaint> adverseReported = cs.stream().filter(c -> c.getCategory() == ComplaintCategory.ADVERSE
                && c.getReportedDate() != null).toList();
        Double reportOnTime = adverseReported.isEmpty() ? null : round1(adverseReported.stream()
                .filter(c -> !c.getReportedDate().isAfter(ComplaintService.reportDeadline(c))).count() * 100.0 / adverseReported.size());
        BigDecimal shipped90 = plan.stream().map(FgRow::shipped).reduce(BigDecimal.ZERO, BigDecimal::add);
        long c90 = cs.stream().filter(c -> !c.getReceivedDate().isBefore(today.minusDays(89))).count();
        Double per10k = shipped90.signum() == 0 ? null : round1(c90 * 10000.0 / shipped90.doubleValue());
        ComplaintStats complaintStats = new ComplaintStats(monthly, open, overdue, per10k, reportOnTime);

        // ---------- 追溯响应：抽最近的成品和物料批次，实际跑一遍全链追溯 ----------
        List<Batch> recent = batches.findTop500ByOrderByIdDesc();
        List<Batch> sample = new ArrayList<>();
        Set<Long> seenItems = new HashSet<>();
        for (Batch b : recent) {
            boolean fg = im.get(b.getItemId()).getItemType() == ItemType.FG;
            long have = sample.stream().filter(x -> (im.get(x.getItemId()).getItemType() == ItemType.FG) == fg).count();
            if (have < (fg ? 10 : 5) && seenItems.add(b.getId())) {
                sample.add(b);
            }
        }
        List<Long> ms = sample.stream().map(b -> trace.trace(b.getBatchNo()).elapsedMs()).toList();
        TraceStats traceStats = new TraceStats(ms.size(), ms.isEmpty() ? 0 : Math.round(ms.stream().mapToLong(Long::longValue).average().orElse(0)),
                ms.stream().mapToLong(Long::longValue).max().orElse(0), TRACE_TARGET_MS);

        Headline headline = new Headline(fgTotal, fgTotal.signum() == 0 ? null : round1(fgNear.doubleValue() * 100 / fgTotal.doubleValue()),
                medianDos, allDone == 0 ? null : round1(allOnTime * 100.0 / allDone), quality.avgReleaseDays(), per10k,
                traceStats.avgMs());
        return new Kpi(time.now(), headline, shelf, supply, slow, factories, quality, complaintStats, traceStats);
    }

    private static Double avg(List<Double> xs) {
        return xs.isEmpty() ? null : round1(xs.stream().mapToDouble(Double::doubleValue).average().orElse(0));
    }

    private static Double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
