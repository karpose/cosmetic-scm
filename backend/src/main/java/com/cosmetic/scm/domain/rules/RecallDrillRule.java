package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.cosmetic.scm.domain.DrillResult;
import com.cosmetic.scm.domain.DrillTarget;

/**
 * 召回演练（模拟召回）的判定（检查要点委托生产版第 24 项）：用时、在外数量的定位率、是否达标，以及多久该再演练一次。
 * 达标 = 每个对象都登记了结果且都联系上、仓库实盘和账面一致、客户反馈的在手数量不多于在外数量、用时不超过目标。
 * 目标用时由企业自定，规则不内置。不依赖框架，有单元测试。
 */
public final class RecallDrillRule {

    /**
     * 一个要联系的对象。expectedQty：客户为在外数量（发出减普通退回），仓库为账面在库，受托厂为空；
     * confirmedQty：客户反馈还在手上的数量（可空）、仓库实盘数量。
     */
    public record Line(int seq, DrillTarget target, String name, BigDecimal expectedQty, DrillResult result, BigDecimal confirmedQty,
                       LocalDateTime contactedAt) {

        public boolean recorded() {
            return result != null && contactedAt != null;
        }
    }

    /**
     * finishedAt：最后一家联系完的时间；elapsedMinutes：从发起到 finishedAt 的分钟数；
     * locatedPct：联系上的客户在外数量占全部在外数量的百分比（一位小数，没有在外数量时为空）。
     */
    public record Outcome(LocalDateTime finishedAt, Long elapsedMinutes, BigDecimal locatedPct, boolean met, List<String> gaps) {
    }

    private RecallDrillRule() {
    }

    /** 还没登记联系结果的对象，每个一句；都登记了返回空。 */
    public static List<String> unrecorded(Collection<Line> lines) {
        return lines.stream().filter(l -> !l.recorded()).map(l -> label(l) + " 还没有登记联系结果").toList();
    }

    public static Outcome evaluate(LocalDateTime startedAt, int targetHours, Collection<Line> lines) {
        List<String> gaps = new ArrayList<>(unrecorded(lines));
        LocalDateTime finished = lines.stream().filter(Line::recorded).map(Line::contactedAt).max(LocalDateTime::compareTo).orElse(null);
        Long minutes = finished == null ? null : Math.max(0, Duration.between(startedAt, finished).toMinutes());
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal located = BigDecimal.ZERO;
        for (Line l : lines) {
            if (l.result() == DrillResult.UNREACHABLE) {
                gaps.add(label(l) + " 联系不上");
            }
            if (l.target() == DrillTarget.CUSTOMER) {
                BigDecimal out = positive(l.expectedQty());
                total = total.add(out);
                if (l.result() == DrillResult.REACHED) {
                    located = located.add(out);
                }
                if (l.result() == DrillResult.REACHED && l.confirmedQty() != null && l.confirmedQty().compareTo(out) > 0) {
                    gaps.add(label(l) + " 反馈在手 " + plain(l.confirmedQty()) + "，多于在外数量 " + plain(out) + "，请核对出库和退货记录");
                }
            }
            if (l.target() == DrillTarget.WAREHOUSE && l.result() == DrillResult.REACHED && l.confirmedQty() != null
                    && l.confirmedQty().compareTo(positive(l.expectedQty())) != 0) {
                gaps.add(label(l) + " 实盘 " + plain(l.confirmedQty()) + "，账面 " + plain(positive(l.expectedQty())));
            }
        }
        if (minutes != null && minutes > targetHours * 60L) {
            gaps.add("用时 " + hours(minutes) + " 小时，超过目标 " + targetHours + " 小时");
        }
        BigDecimal pct = total.signum() == 0 ? null : located.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.DOWN);
        return new Outcome(finished, minutes, pct, gaps.isEmpty(), List.copyOf(gaps));
    }

    /** 分钟换成小时，一位小数。 */
    public static String hours(long minutes) {
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * 该不该再演练：有过成品出库，而且从来没有审核过的演练、或最近一次审核过的演练发起已超过 intervalMonths 个月。
     * 还没出过货（上线前）不算到期。
     */
    public static boolean due(boolean hasShipments, LocalDate lastDrill, LocalDate today, int intervalMonths) {
        if (!hasShipments) {
            return false;
        }
        return lastDrill == null || lastDrill.plusMonths(intervalMonths).isBefore(today);
    }

    private static String label(Line l) {
        return l.target().getLabel() + " " + Objects.requireNonNullElse(l.name(), String.valueOf(l.seq()));
    }

    private static BigDecimal positive(BigDecimal v) {
        return v == null || v.signum() < 0 ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
