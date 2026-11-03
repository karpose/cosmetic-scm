package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 配方合规校验：禁用原料不得使用，限用原料不得超过浓度上限。
 * 浓度 = 每件单耗 ÷ 成品净含量。纯函数。
 */
public final class FormulaCompliance {

    private FormulaCompliance() {
    }

    public enum Level { OK, NOT_APPLICABLE, BANNED, OVER_LIMIT }

    public record Check(Level level, BigDecimal concentrationPct, BigDecimal limitPct, String message) {
        public boolean blocking() {
            return level == Level.BANNED || level == Level.OVER_LIMIT;
        }
    }

    /**
     * 单耗换算为克；单位不是质量单位时返回 null（无法计算浓度）。
     */
    public static BigDecimal toGrams(BigDecimal qty, String unit) {
        if (qty == null || unit == null) {
            return null;
        }
        String u = unit.trim().toLowerCase();
        return switch (u) {
            case "kg", "千克", "公斤" -> qty.multiply(BigDecimal.valueOf(1000));
            case "g", "克" -> qty;
            case "mg", "毫克" -> qty.divide(BigDecimal.valueOf(1000), 9, RoundingMode.HALF_UP);
            default -> null;
        };
    }

    /**
     * @param materialName 原料名称，用于提示
     * @param isRawMaterial 只有原料参与浓度校验，包材直接返回 NOT_APPLICABLE
     * @param banned        是否禁用原料
     * @param limitPct      限用浓度上限 %，为空表示不限
     * @param qtyPer        每件成品单耗（按物料单位）
     * @param unit          物料单位
     * @param netGrams      成品净含量（g），为空则无法计算浓度
     */
    public static Check check(String materialName, boolean isRawMaterial, boolean banned, BigDecimal limitPct,
                              BigDecimal qtyPer, String unit, BigDecimal netGrams) {
        if (!isRawMaterial) {
            return new Check(Level.NOT_APPLICABLE, null, null, "包材不做浓度校验");
        }
        if (banned) {
            return new Check(Level.BANNED, null, null, materialName + " 为禁用原料，不得进入配方");
        }
        BigDecimal grams = toGrams(qtyPer, unit);
        if (grams == null || netGrams == null || netGrams.signum() <= 0) {
            String msg = limitPct == null ? "无限量要求" : "缺少净含量或质量单位，无法校验限量 " + limitPct.stripTrailingZeros().toPlainString() + "%";
            return new Check(Level.NOT_APPLICABLE, null, limitPct, msg);
        }
        BigDecimal pct = grams.multiply(BigDecimal.valueOf(100)).divide(netGrams, 4, RoundingMode.HALF_UP);
        if (limitPct != null && pct.compareTo(limitPct) > 0) {
            return new Check(Level.OVER_LIMIT, pct, limitPct,
                    materialName + " 浓度 " + pct.stripTrailingZeros().toPlainString() + "% 超出限量 "
                            + limitPct.stripTrailingZeros().toPlainString() + "%");
        }
        String msg = limitPct == null
                ? "浓度 " + pct.stripTrailingZeros().toPlainString() + "%"
                : "浓度 " + pct.stripTrailingZeros().toPlainString() + "%，限量 " + limitPct.stripTrailingZeros().toPlainString() + "%";
        return new Check(Level.OK, pct, limitPct, msg);
    }
}
