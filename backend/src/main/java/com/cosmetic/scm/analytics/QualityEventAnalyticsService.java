package com.cosmetic.scm.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.complaint.ComplaintService;
import com.cosmetic.scm.complaint.ComplaintService.ComplaintView;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.ComplaintCategory;
import com.cosmetic.scm.domain.Severity;
import com.cosmetic.scm.recall.RecallService;
import com.cosmetic.scm.recall.RecallService.RecallView;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客诉、不良反应与召回效率深度分析（V48）：
 * 按类别、来源渠道、成品汇总投诉分布与不良反应按时上报率，并计算每次召回的通知完成率与实际回收率。
 */
@Service
@RequiredArgsConstructor
public class QualityEventAnalyticsService {

    private final ComplaintService complaints;
    private final RecallService recalls;
    private final TimeProvider time;

    public record DimensionRow(String key, String label, long count, long adverseCount, long seriousCount,
                               long openCount, Double sharePct) {
    }

    public record RecallMetricRow(String recallNo, String batchNo, String itemName, String statusLabel,
                                  BigDecimal shippedQty, BigDecimal outstandingQty, BigDecimal recoveredQty,
                                  Double notifyRatePct, Double recoveryRatePct, int customerCount,
                                  long notifiedCount, LocalDateTime openedAt, LocalDateTime closedAt) {
    }

    public record QualityEventReport(LocalDate from, LocalDate to,
                                     long totalComplaints, long openComplaints, long closedComplaints,
                                     Double closeRatePct,
                                     long adverseTotal, long adverseReportedOnTime, long adverseOverdue,
                                     Double adverseOnTimeRatePct,
                                     long recallTotal, long recallOpen, long recallClosed,
                                     Double avgRecallNotifyRatePct, Double avgRecallRecoveryRatePct,
                                     List<DimensionRow> byCategory, List<DimensionRow> byChannel,
                                     List<DimensionRow> byItem, List<RecallMetricRow> recalls) {
    }

    @Transactional(readOnly = true)
    public QualityEventReport report(LocalDate from, LocalDate to) {
        LocalDate today = time.today();
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(364);

        List<ComplaintView> cs = complaints.listAll().stream()
                .filter(c -> c.receivedDate() != null
                        && !c.receivedDate().isBefore(start)
                        && !c.receivedDate().isAfter(end))
                .toList();

        long total = cs.size();
        long open = cs.stream().filter(c -> c.status() == CaseStatus.OPEN).count();
        long closed = total - open;
        Double closeRate = pct(closed, total);

        List<ComplaintView> adverse = cs.stream().filter(c -> c.category() == ComplaintCategory.ADVERSE).toList();
        long adverseTotal = adverse.size();
        long onTime = 0;
        long overdue = 0;
        long evaluableAdverse = 0;
        for (ComplaintView c : adverse) {
            LocalDate deadline = c.reportDeadline();
            if (c.reportedDate() != null) {
                evaluableAdverse++;
                if (deadline == null || !c.reportedDate().isAfter(deadline)) {
                    onTime++;
                } else {
                    overdue++;
                }
            } else if ("OVERDUE".equals(c.reportState())) {
                evaluableAdverse++;
                overdue++;
            }
        }
        Double adverseOnTimeRate = pct(onTime, evaluableAdverse);

        List<DimensionRow> byCategory = group(cs, total, c -> c.category().name(), ComplaintView::categoryLabel);
        List<DimensionRow> byChannel = group(cs, total, c -> c.channel() == null ? "未知渠道" : c.channel(),
                c -> c.channel() == null ? "未知渠道" : c.channel());
        List<DimensionRow> byItem = group(cs, total, c -> String.valueOf(c.itemId()), ComplaintView::itemName);

        List<RecallView> rs = recalls.listAll().stream()
                .filter(r -> r.openedAt() != null
                        && !r.openedAt().toLocalDate().isBefore(start)
                        && !r.openedAt().toLocalDate().isAfter(end))
                .toList();

        List<RecallMetricRow> recallRows = new ArrayList<>(rs.size());
        double notifySum = 0;
        int notifyCount = 0;
        double recoverySum = 0;
        int recoveryCount = 0;
        for (RecallView r : rs) {
            int custCount = r.notices() == null ? 0 : r.notices().size();
            Double notifyRate = custCount > 0 ? pct(r.notifiedCount(), custCount) : null;
            BigDecimal shipped = r.shippedQty() == null ? BigDecimal.ZERO : r.shippedQty();
            BigDecimal returned = r.returnedQty() == null ? BigDecimal.ZERO : r.returnedQty();
            BigDecimal outstanding = shipped.subtract(returned).max(BigDecimal.ZERO);
            BigDecimal recovered = r.recoveredQty() == null ? BigDecimal.ZERO : r.recoveredQty();
            Double recoveryRate = outstanding.signum() > 0
                    ? Math.min(100.0, Math.round(recovered.doubleValue() * 1000.0 / outstanding.doubleValue()) / 10.0)
                    : null;
            if (notifyRate != null) {
                notifySum += notifyRate;
                notifyCount++;
            }
            if (recoveryRate != null) {
                recoverySum += recoveryRate;
                recoveryCount++;
            }
            recallRows.add(new RecallMetricRow(r.recallNo(), r.batchNo(), r.itemName(), r.statusLabel(),
                    shipped, outstanding, recovered, notifyRate, recoveryRate,
                    custCount, r.notifiedCount(), r.openedAt(), r.closedAt()));
        }
        long recallOpen = rs.stream().filter(r -> r.status() == CaseStatus.OPEN).count();
        long recallClosed = rs.size() - recallOpen;
        Double avgNotify = notifyCount == 0 ? null : Math.round(notifySum * 10.0 / notifyCount) / 10.0;
        Double avgRecovery = recoveryCount == 0 ? null : Math.round(recoverySum * 10.0 / recoveryCount) / 10.0;

        return new QualityEventReport(start, end, total, open, closed, closeRate,
                adverseTotal, onTime, overdue, adverseOnTimeRate,
                rs.size(), recallOpen, recallClosed, avgNotify, avgRecovery,
                byCategory, byChannel, byItem, recallRows);
    }

    private static List<DimensionRow> group(List<ComplaintView> list, long total,
                                            java.util.function.Function<ComplaintView, String> keyFn,
                                            java.util.function.Function<ComplaintView, String> labelFn) {
        Map<String, List<ComplaintView>> byKey = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (ComplaintView c : list) {
            String k = keyFn.apply(c);
            byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
            labels.putIfAbsent(k, labelFn.apply(c));
        }
        return byKey.entrySet().stream()
                .map(e -> {
                    List<ComplaintView> items = e.getValue();
                    long adv = items.stream().filter(c -> c.category() == ComplaintCategory.ADVERSE).count();
                    long ser = items.stream().filter(c -> c.severity() == Severity.SERIOUS).count();
                    long op = items.stream().filter(c -> c.status() == CaseStatus.OPEN).count();
                    return new DimensionRow(e.getKey(), labels.get(e.getKey()), items.size(), adv, ser, op, pct(items.size(), total));
                })
                .sorted(Comparator.comparingLong(DimensionRow::count).reversed().thenComparing(DimensionRow::label))
                .toList();
    }

    private static Double pct(long num, long den) {
        return den <= 0 ? null : Math.round(num * 1000.0 / den) / 10.0;
    }
}
