package com.cosmetic.scm.domain.rules;

import java.time.DayOfWeek;
import java.time.LocalDate;

import com.cosmetic.scm.domain.RetainSiteKind;

/**
 * 留样地点（《化妆品生产质量管理规范》第五十六条；检查要点委托生产版第 15 项）：
 * 委托方在住所或主要经营场所留样，也可以在住所或主要经营场所所在地的其他经营场所留样；
 * 留样地点不是住所或主要经营场所的，要在首次留样之日起 20 个工作日内，把留样地点的地址等信息报告所在地负责药品监督管理的部门。
 * <p>
 * 工作日按周一至周五算，不扣法定节假日、也不算调休上班的周末：节假日只会让真正的截止日更晚，所以这样算出的截止日不会晚于实际。
 * 不依赖框架，有单元测试。
 */
public final class RetainSiteRule {

    /** 首次留样之日起多少个工作日内报告。 */
    public static final int REPORT_WORKING_DAYS = 20;

    public enum State {
        /** 住所或主要经营场所，不需要报告。 */
        NOT_REQUIRED("不需要报告", false),
        /** 其他经营场所，但不在住所或主要经营场所所在地：不能作为留样地点。 */
        NOT_ALLOWED("不符合要求", true),
        /** 其他经营场所，还没有留样，也还没报告。 */
        NOT_USED("还没有留样", false),
        /** 已经留样，还没报告，没过截止日。 */
        PENDING("待报告", false),
        /** 已经留样，过了截止日还没报告。 */
        OVERDUE("逾期未报告", true),
        REPORTED("已报告", false),
        /** 报告了，但晚于截止日。 */
        REPORTED_LATE("逾期后报告", true);

        private final String label;
        private final boolean problem;

        State(String label, boolean problem) {
            this.label = label;
            this.problem = problem;
        }

        public String getLabel() {
            return label;
        }

        /** 自查要标出来的情况。 */
        public boolean problem() {
            return problem;
        }
    }

    private RetainSiteRule() {
    }

    public static boolean reportRequired(RetainSiteKind kind) {
        return kind == RetainSiteKind.OTHER_PREMISES;
    }

    /** 首次留样之日起第 n 个工作日（首次留样当天不算）。 */
    public static LocalDate addWorkingDays(LocalDate start, int n) {
        LocalDate d = start;
        int left = n;
        while (left > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return d;
    }

    /** 报告截止日；不需要报告或还没留样的返回 null。 */
    public static LocalDate deadline(RetainSiteKind kind, LocalDate firstRetain) {
        return reportRequired(kind) && firstRetain != null ? addWorkingDays(firstRetain, REPORT_WORKING_DAYS) : null;
    }

    /** 两个日期取早的（都为空返回空）：上线前开始使用的日期和台账里最早一份留样。 */
    public static LocalDate earliest(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isBefore(b) ? a : b;
    }

    /**
     * 截至 asOf 这天的状态。asOf 之后才登记的报告当作还没报告（自查看的是期间结束时的情况）；
     * 首次留样在 asOf 之后的当作还没留样。
     */
    public static State state(RetainSiteKind kind, boolean sameLocality, LocalDate firstRetain, LocalDate reportDate, LocalDate asOf) {
        if (!reportRequired(kind)) {
            return State.NOT_REQUIRED;
        }
        if (!sameLocality) {
            return State.NOT_ALLOWED;
        }
        LocalDate first = firstRetain != null && !firstRetain.isAfter(asOf) ? firstRetain : null;
        LocalDate reported = reportDate != null && !reportDate.isAfter(asOf) ? reportDate : null;
        if (reported != null) {
            return first != null && reported.isAfter(deadline(kind, first)) ? State.REPORTED_LATE : State.REPORTED;
        }
        if (first == null) {
            return State.NOT_USED;
        }
        return asOf.isAfter(deadline(kind, first)) ? State.OVERDUE : State.PENDING;
    }
}
