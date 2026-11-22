package com.cosmetic.scm.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
 * 客诉、不良反应与召回效率深度分析及监管合规闭环监控（V48 / V68）：
 * 按类别、来源渠道、成品汇总投诉分布与不良反应按时上报率，计算召回通知完成率与实际回收率，
 * 并提供不良反应法定上报倒计时与召回执行滞后预警看板。
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

    public record ClosureItemRow(String itemType, String itemTypeLabel, String refNo, String itemName,
                                 String batchNo, String detail, LocalDate dueDate, Long daysLeft,
                                 Double progressPct, String riskLevel, String riskLabel,
                                 String recommendedAction, String route) {
    }

    public record QualityEventClosureBoard(int totalCount, int overdueCount, int dueSoonCount,
                                           int recallLagCount, int openComplaintCount,
                                           List<ClosureItemRow> rows) {
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

    @Transactional(readOnly = true)
    public QualityEventClosureBoard closureBoard() {
        LocalDate today = time.today();
        List<ComplaintView> openComplaints = complaints.open();
        List<RecallView> openRecalls = recalls.open();

        List<ClosureItemRow> rows = new ArrayList<>();
        int overdueCnt = 0;
        int dueSoonCnt = 0;
        int recallLagCnt = 0;

        for (ComplaintView c : openComplaints) {
            if (c.category() != ComplaintCategory.ADVERSE || c.reportedDate() != null) {
                continue;
            }
            LocalDate deadline = c.reportDeadline();
            Long daysLeft = deadline == null ? null : ChronoUnit.DAYS.between(today, deadline);
            String riskLevel;
            String riskLabel;
            String action;
            if ("OVERDUE".equals(c.reportState()) || (daysLeft != null && daysLeft < 0)) {
                riskLevel = "OVERDUE";
                riskLabel = "上报已超期";
                action = "不良反应已超法定上报时限，请立即补报国家监测平台并登记上报编号";
                overdueCnt++;
            } else if (daysLeft != null && daysLeft <= 3) {
                riskLevel = "DUE_SOON";
                riskLabel = daysLeft == 0 ? "今日截止上报" : "3天内须上报";
                action = daysLeft == 0
                        ? "今日为法定上报截止日，请务必今日完成上报并录入编号"
                        : "距法定上报截止仅 " + daysLeft + " 天，请优先完成不良反应上报";
                dueSoonCnt++;
            } else {
                riskLevel = "NORMAL";
                riskLabel = "待上报";
                action = "请于 " + (deadline == null ? "法定期限" : deadline) + " 前完成监管上报并登记编号";
            }
            String ch = c.channel() == null ? "未知渠道" : c.channel();
            rows.add(new ClosureItemRow(
                    "ADVERSE_REPORT", "不良反应上报", c.complaintNo(), c.itemName(), c.batchNo(),
                    c.severityLabel() + " · " + ch, deadline, daysLeft, 0.0,
                    riskLevel, riskLabel, action, "/quality-events?tab=complaints"));
        }

        for (RecallView r : openRecalls) {
            int custCount = r.notices() == null ? 0 : r.notices().size();
            long notified = r.notifiedCount();
            BigDecimal shipped = r.shippedQty() == null ? BigDecimal.ZERO : r.shippedQty();
            BigDecimal returned = r.returnedQty() == null ? BigDecimal.ZERO : r.returnedQty();
            BigDecimal outstanding = shipped.subtract(returned).max(BigDecimal.ZERO);
            BigDecimal recovered = r.recoveredQty() == null ? BigDecimal.ZERO : r.recoveredQty();
            Double recoveryRate = outstanding.signum() > 0
                    ? Math.min(100.0, Math.round(recovered.doubleValue() * 1000.0 / outstanding.doubleValue()) / 10.0)
                    : 100.0;
            long openDays = r.openedAt() == null ? 0L
                    : ChronoUnit.DAYS.between(r.openedAt().toLocalDate(), today);
            boolean notifyLag = custCount > 0 && notified < custCount;
            boolean recoveryLag = recoveryRate < 80.0;

            String riskLevel;
            String riskLabel;
            String action;
            if (notifyLag && openDays >= 3) {
                riskLevel = "OVERDUE";
                riskLabel = "召回通知滞后";
                action = "召回已发起 " + openDays + " 天，仍有 " + (custCount - notified) + " 家客户未确认通知，请立即催办";
                overdueCnt++;
            } else if (notifyLag || recoveryLag) {
                riskLevel = "RECALL_LAG";
                riskLabel = notifyLag ? "待完成客户通知" : "回收率偏低";
                action = notifyLag
                        ? "请尽快完成剩余 " + (custCount - notified) + " 家客户召回通知"
                        : "当前回收率 " + recoveryRate + "%，请持续跟进客户退货入库";
                recallLagCnt++;
            } else {
                riskLevel = "NORMAL";
                riskLabel = "可评估结案";
                action = "客户已全部通知且回收率达标，请质量负责人审核后关闭召回";
            }
            String detail = "通知 " + notified + "/" + custCount + " 家 · 回收 "
                    + recovered.stripTrailingZeros().toPlainString() + "/"
                    + outstanding.stripTrailingZeros().toPlainString();
            rows.add(new ClosureItemRow(
                    "RECALL_CLOSURE", "召回执行闭环", r.recallNo(), r.itemName(), r.batchNo(),
                    detail, null, null, recoveryRate, riskLevel, riskLabel, action,
                    "/quality-events?tab=recalls&recall=" + r.id()));
        }

        rows.sort(Comparator.comparingInt((ClosureItemRow x) -> closureRiskRank(x.riskLevel()))
                .thenComparing(ClosureItemRow::daysLeft, Comparator.nullsLast(Comparator.naturalOrder())));

        return new QualityEventClosureBoard(rows.size(), overdueCnt, dueSoonCnt, recallLagCnt,
                openComplaints.size(), rows);
    }

    private static int closureRiskRank(String level) {
        return switch (level) {
            case "OVERDUE" -> 0;
            case "DUE_SOON" -> 1;
            case "RECALL_LAG" -> 2;
            default -> 3;
        };
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
