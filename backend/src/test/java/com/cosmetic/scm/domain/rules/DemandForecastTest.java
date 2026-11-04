package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class DemandForecastTest {

    private static List<BigDecimal> months(int... qty) {
        List<BigDecimal> out = new ArrayList<>();
        for (int q : qty) {
            out.add(BigDecimal.valueOf(q));
        }
        return out;
    }

    @Test
    void noSalesMeansNoForecast() {
        assertThat(DemandForecast.forecast(List.of(), 3).method()).isEqualTo(DemandForecast.Method.NONE);
        DemandForecast.Result r = DemandForecast.forecast(months(0, 0, 0, 0), 3);
        assertThat(r.method()).isEqualTo(DemandForecast.Method.NONE);
        assertThat(r.next()).allMatch(v -> v.signum() == 0);
    }

    @Test
    void shortHistoryUsesRecentAverage() {
        DemandForecast.Result r = DemandForecast.forecast(months(1000, 100, 200, 300), 2);
        assertThat(r.method()).isEqualTo(DemandForecast.Method.AVERAGE);
        assertThat(r.next()).hasSize(2);
        assertThat(r.next().get(0)).isEqualByComparingTo("200");
    }

    @Test
    void oneYearOfHistoryRepeatsLastYearSameMonth() {
        // 正好 12 个月：没有同期可比，增长率 1，下个月 = 去年同月
        DemandForecast.Result r = DemandForecast.forecast(months(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120), 3);
        assertThat(r.method()).isEqualTo(DemandForecast.Method.YEAR_ON_YEAR);
        assertThat(r.next().get(0)).isEqualByComparingTo("10");
        assertThat(r.next().get(2)).isEqualByComparingTo("30");
    }

    @Test
    void yearOnYearGrowthIsCappedSoOneGoodQuarterDoesNotRunAway() {
        // 近 3 个月 300 ，去年同期 30：真实增长 10 倍，封顶 2 倍
        DemandForecast.Result r = DemandForecast.forecast(months(10, 10, 10, 50, 50, 50, 50, 50, 50, 50, 50, 50, 100, 100, 100), 1);
        assertThat(r.next().get(0)).isEqualByComparingTo("100"); // 去年同月 50 × 2
    }

    @Test
    void twoYearsCaptureSeasonalPeak() {
        // 每年 11 月（第 11 个位置）是旺季，其它月 100；历史到 12 月结束，要预测次年 1–3 月和年底
        List<BigDecimal> h = new ArrayList<>();
        for (int y = 0; y < 2; y++) {
            for (int m = 0; m < 12; m++) {
                h.add(BigDecimal.valueOf(m == 10 ? 1000 : 100));
            }
        }
        DemandForecast.Result r = DemandForecast.forecast(h, 12);
        assertThat(r.method()).isEqualTo(DemandForecast.Method.SEASONAL);
        // 平常月接近 100，旺季接近 1000：比移动平均（近 3 个月 400）有用得多
        assertThat(r.next().get(0).intValue()).isBetween(90, 130);
        assertThat(r.next().get(10).intValue()).isBetween(900, 1300);
    }

    @Test
    void seasonalLevelFollowsRecentGrowth() {
        // 第二年全年翻倍：基础水平跟随近期，而不是两年平均
        List<BigDecimal> h = new ArrayList<>();
        for (int m = 0; m < 12; m++) {
            h.add(BigDecimal.valueOf(100));
        }
        for (int m = 0; m < 12; m++) {
            h.add(BigDecimal.valueOf(200));
        }
        assertThat(DemandForecast.forecast(h, 1).next().get(0).intValue()).isBetween(180, 220);
    }

    @Test
    void usesAtMostThreeYearsOfCompleteCycles() {
        // 很久以前的高销量不影响预测
        List<BigDecimal> h = new ArrayList<>();
        for (int m = 0; m < 24; m++) {
            h.add(BigDecimal.valueOf(5000));
        }
        for (int m = 0; m < 36; m++) {
            h.add(BigDecimal.valueOf(100));
        }
        assertThat(DemandForecast.forecast(h, 1).next().get(0)).isEqualByComparingTo("100");
    }
}
