package com.cosmetic.scm.domain.rules;

/**
 * 供应商综合评分：只作展示，不拦下单（能不能下单只看合格名录和资质）。纯函数，不依赖框架。
 * 质量 50、交货 30、审核 20。哪一项没有数据就不计，其余按比例放大；全都没有时没有评分。
 * 权重和等级线是默认值，质量部可以改。
 */
public final class SupplierScore {

    private static final double QUALITY_WEIGHT = 50;
    private static final double DELIVERY_WEIGHT = 30;
    private static final double AUDIT_WEIGHT = 20;

    private SupplierScore() {
    }

    public enum AuditLevel { PASS, CONDITIONAL, FAIL }

    /** 得分 0 到 100，等级 A / B / C / D。 */
    public record Result(int score, String grade) {
    }

    /**
     * @param received  统计期内来料批次数
     * @param rejected  其中拒收的批次数
     * @param dueTotal  统计期内已经到交期的采购单数
     * @param onTime    其中按时到齐的数量
     * @param audit     最近一次审核结论，没审核过为 null
     * @return 没有任何数据时返回 null
     */
    public static Result of(long received, long rejected, long dueTotal, long onTime, AuditLevel audit) {
        double points = 0;
        double weight = 0;
        if (received > 0) {
            points += QUALITY_WEIGHT * clamp(1d - (double) rejected / received);
            weight += QUALITY_WEIGHT;
        }
        if (dueTotal > 0) {
            points += DELIVERY_WEIGHT * clamp((double) onTime / dueTotal);
            weight += DELIVERY_WEIGHT;
        }
        if (audit != null) {
            points += AUDIT_WEIGHT * switch (audit) {
                case PASS -> 1d;
                case CONDITIONAL -> 0.6d;
                case FAIL -> 0d;
            };
            weight += AUDIT_WEIGHT;
        }
        if (weight == 0) {
            return null;
        }
        int score = (int) Math.round(points * 100 / weight);
        return new Result(score, score >= 90 ? "A" : score >= 75 ? "B" : score >= 60 ? "C" : "D");
    }

    private static double clamp(double v) {
        return Math.max(0d, Math.min(1d, v));
    }
}
