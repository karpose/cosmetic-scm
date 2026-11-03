package com.cosmetic.scm.domain.rules;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 效期计算。纯函数，不依赖框架，便于单元测试。 */
public final class ShelfLife {

    private ShelfLife() {
    }

    /**
     * 剩余效期占总效期的比例，0 到 1。
     * 缺少生产日期或有效期、或有效期不晚于生产日期时返回 null（例如包材不管效期）。
     */
    public static Double remainingRatio(LocalDate mfgDate, LocalDate expDate, LocalDate today) {
        if (mfgDate == null || expDate == null || today == null) {
            return null;
        }
        long total = ChronoUnit.DAYS.between(mfgDate, expDate);
        if (total <= 0) {
            return null;
        }
        long left = ChronoUnit.DAYS.between(today, expDate);
        double ratio = (double) left / total;
        return Math.max(0d, Math.min(1d, ratio));
    }

    /** 按保质期月数推算有效期；月数为空或不大于 0 时返回 null。 */
    public static LocalDate expiryFrom(LocalDate mfgDate, Integer shelfMonths) {
        if (mfgDate == null || shelfMonths == null || shelfMonths <= 0) {
            return null;
        }
        return mfgDate.plusMonths(shelfMonths);
    }

    /** 已过有效期（有效期当天仍可用）。 */
    public static boolean isExpired(LocalDate expDate, LocalDate today) {
        return expDate != null && expDate.isBefore(today);
    }

    /** 留样保存期限：不少于产品使用期限届满后 6 个月（《化妆品生产质量管理规范》第十八条）。 */
    public static LocalDate retainUntil(LocalDate expDate) {
        return expDate == null ? null : expDate.plusMonths(6);
    }
}
