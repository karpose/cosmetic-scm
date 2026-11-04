package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 补货计算（再订货点法），纯规则，不依赖框架：
 * <pre>
 *   日均需求   = 观察期出库量 / 观察天数
 *   安全库存   = 日均需求 × 安全天数
 *   再订货点   = 日均需求 × 交付周期天数 + 安全库存
 *   预计可用   = 在库可售 + 在途（待放行、在制、未到货采购）
 *   预计可用 < 再订货点 时，建议量 = 再订货点 + 日均需求 × 覆盖天数 − 预计可用，按批量向上取整
 * </pre>
 * 没有历史需求时不建议补货（日均为 0）。
 */
public final class Replenishment {

    private Replenishment() {
    }

    public record Params(int windowDays, int leadDays, int safetyDays, int coverDays) {
        public Params {
            if (windowDays <= 0 || leadDays < 0 || safetyDays < 0 || coverDays < 0) {
                throw new IllegalArgumentException("观察天数须大于 0，其余天数不能为负");
            }
        }
    }

    /**
     * @param daysOfSupply 预计可用还能卖多少天；没有需求时为 null
     */
    public record Result(BigDecimal dailyDemand, BigDecimal safetyStock, BigDecimal reorderPoint, BigDecimal projected,
                         BigDecimal suggested, Integer daysOfSupply, boolean belowReorderPoint) {
    }

    public static Result calc(BigDecimal shippedInWindow, BigDecimal available, BigDecimal pipeline, Params p,
                              BigDecimal lotSize) {
        BigDecimal daily = nz(shippedInWindow).divide(BigDecimal.valueOf(p.windowDays()), 4, RoundingMode.HALF_UP);
        return calcFromDaily(daily, available, pipeline, p, lotSize);
    }

    /** 日均需求由外部给出（如按预测算出的未来日均），其余算法相同。 */
    public static Result calcFromDaily(BigDecimal daily, BigDecimal available, BigDecimal pipeline, Params p, BigDecimal lotSize) {
        BigDecimal safety = daily.multiply(BigDecimal.valueOf(p.safetyDays())).setScale(0, RoundingMode.UP);
        BigDecimal reorder = daily.multiply(BigDecimal.valueOf(p.leadDays())).setScale(0, RoundingMode.UP).add(safety);
        BigDecimal projected = nz(available).add(nz(pipeline));
        Integer days = daily.signum() == 0 ? null : projected.divide(daily, 0, RoundingMode.DOWN).intValue();
        boolean below = daily.signum() > 0 && projected.compareTo(reorder) < 0;
        BigDecimal suggested = BigDecimal.ZERO;
        if (below) {
            BigDecimal target = reorder.add(daily.multiply(BigDecimal.valueOf(p.coverDays())));
            suggested = roundUp(target.subtract(projected), lotSize);
        }
        return new Result(daily, safety, reorder, projected, suggested, days, below);
    }

    /** 物料净需求：毛需求 − 可用（在库 + 待检 + 在途采购），不足部分按批量向上取整；够用时为 0。 */
    public static BigDecimal netRequirement(BigDecimal gross, BigDecimal available, BigDecimal lotSize) {
        BigDecimal net = nz(gross).subtract(nz(available));
        return net.signum() <= 0 ? BigDecimal.ZERO : roundUp(net, lotSize);
    }

    static BigDecimal roundUp(BigDecimal qty, BigDecimal lotSize) {
        if (qty.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (lotSize == null || lotSize.signum() <= 0) {
            return qty.setScale(4, RoundingMode.UP).stripTrailingZeros();
        }
        return qty.divide(lotSize, 0, RoundingMode.UP).multiply(lotSize);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
