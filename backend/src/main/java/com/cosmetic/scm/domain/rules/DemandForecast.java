package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 按月需求预测，纯规则，不依赖框架。输入是从首次出库到上个完整自然月的月度出库量（旧 → 新，没卖的月份为 0），
 * 按历史长度选方法，历史越长越能看出季节：
 * <pre>
 *   满 24 个月   季节指数：每个自然月的平均量 / 总平均 = 指数；近 3 个月去季节后的平均 × 目标月指数
 *   12–23 个月  同比：去年同月 × 增长率（近 3 个月 / 去年同期 3 个月，限制在 0.5–2 倍）
 *   不足 12 个月 移动平均：近 3 个月平均，看不出季节
 * </pre>
 * 不含促销、新渠道等一次性因素，这些要人工判断。
 */
public final class DemandForecast {

    private DemandForecast() {
    }

    /** 最多用多少个月历史：3 年足够看季节，太久的数据反映不了现在。 */
    public static final int MAX_HISTORY_MONTHS = 36;

    private static final BigDecimal MIN_GROWTH = new BigDecimal("0.5");
    private static final BigDecimal MAX_GROWTH = new BigDecimal("2");

    public enum Method {
        SEASONAL("季节指数"), YEAR_ON_YEAR("同比"), AVERAGE("移动平均"), NONE("无历史");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public record Result(Method method, List<BigDecimal> next) {
    }

    public static Result forecast(List<BigDecimal> history, int horizon) {
        if (horizon <= 0 || horizon > 12) {
            throw new IllegalArgumentException("预测期须在 1 到 12 个月之间");
        }
        int n = history.size();
        if (n == 0 || history.stream().allMatch(v -> v.signum() == 0)) {
            return flat(Method.NONE, BigDecimal.ZERO, horizon);
        }
        if (n >= 24) {
            return seasonal(history, horizon);
        }
        if (n >= 12) {
            return yearOnYear(history, horizon);
        }
        return flat(Method.AVERAGE, mean(history.subList(Math.max(0, n - 3), n)), horizon);
    }

    private static Result flat(Method m, BigDecimal value, int horizon) {
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < horizon; i++) {
            out.add(whole(value));
        }
        return new Result(m, out);
    }

    private static Result yearOnYear(List<BigDecimal> h, int horizon) {
        int n = h.size();
        // 不足 15 个月时没有去年同期的 3 个月可比，增长率按 1
        BigDecimal growth = BigDecimal.ONE;
        if (n >= 15) {
            BigDecimal lastYear = sum(h.subList(n - 15, n - 12));
            if (lastYear.signum() > 0) {
                growth = sum(h.subList(n - 3, n)).divide(lastYear, 4, RoundingMode.HALF_UP).max(MIN_GROWTH).min(MAX_GROWTH);
            }
        }
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < horizon; i++) {
            out.add(whole(h.get(n - 12 + i).multiply(growth)));
        }
        return new Result(Method.YEAR_ON_YEAR, out);
    }

    private static Result seasonal(List<BigDecimal> history, int horizon) {
        // 只用完整的年份，否则部分月份多算一轮，指数会偏
        int k = Math.min(history.size(), MAX_HISTORY_MONTHS);
        k -= k % 12;
        List<BigDecimal> h = history.subList(history.size() - k, history.size());
        BigDecimal overall = mean(h);
        BigDecimal[] index = new BigDecimal[12];
        for (int p = 0; p < 12; p++) {
            BigDecimal total = BigDecimal.ZERO;
            for (int t = p; t < k; t += 12) {
                total = total.add(h.get(t));
            }
            index[p] = total.divide(BigDecimal.valueOf(k / 12), 6, RoundingMode.HALF_UP).divide(overall, 6, RoundingMode.HALF_UP);
        }
        // 去季节后的当前水平；指数为 0 的月份（固定不卖）没有信息，跳过
        BigDecimal level = BigDecimal.ZERO;
        int used = 0;
        for (int t = k - 3; t < k; t++) {
            BigDecimal idx = index[t % 12];
            if (idx.signum() > 0) {
                level = level.add(h.get(t).divide(idx, 6, RoundingMode.HALF_UP));
                used++;
            }
        }
        level = used == 0 ? overall : level.divide(BigDecimal.valueOf(used), 6, RoundingMode.HALF_UP);
        List<BigDecimal> out = new ArrayList<>();
        for (int i = 0; i < horizon; i++) {
            out.add(whole(level.multiply(index[(k + i) % 12])));
        }
        return new Result(Method.SEASONAL, out);
    }

    private static BigDecimal mean(List<BigDecimal> list) {
        return sum(list).divide(BigDecimal.valueOf(list.size()), 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<BigDecimal> list) {
        return list.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal whole(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP);
    }
}
