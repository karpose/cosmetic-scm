package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;

/** 检测项目的上下限判定。质量标准、稳定性试验共用。上下限都是闭区间。 */
public final class SpecLimit {

    private SpecLimit() {
    }

    public static boolean hasLimits(BigDecimal min, BigDecimal max) {
        return min != null || max != null;
    }

    public static boolean within(BigDecimal min, BigDecimal max, BigDecimal value) {
        return (min == null || value.compareTo(min) >= 0) && (max == null || value.compareTo(max) <= 0);
    }

    /** “5 ~ 7 %”“≥ 98 %”“≤ 100 cfu/g”，没有上下限时返回空串。 */
    public static String text(BigDecimal min, BigDecimal max, String unit) {
        String u = unit == null || unit.isBlank() ? "" : " " + unit.trim();
        if (min != null && max != null) {
            return plain(min) + " ~ " + plain(max) + u;
        }
        if (min != null) {
            return "≥ " + plain(min) + u;
        }
        return max == null ? "" : "≤ " + plain(max) + u;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
