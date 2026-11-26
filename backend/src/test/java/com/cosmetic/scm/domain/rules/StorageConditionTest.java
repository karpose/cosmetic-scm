package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class StorageConditionTest {

    private static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    private static StorageCondition.Limits limits(String tMin, String tMax, String rMin, String rMax) {
        return new StorageCondition.Limits(d(tMin), d(tMax), d(rMin), d(rMax));
    }

    @Test
    void limitsText() {
        assertThat(limits("15", "30", null, "75").text()).isEqualTo("温度 15–30℃，湿度 ≤75%");
        assertThat(limits("2.0", null, null, null).text()).isEqualTo("温度 ≥2℃");
        assertThat(limits(null, null, "35", "75").text()).isEqualTo("湿度 35–75%");
        assertThat(limits(null, null, null, null).text()).isEqualTo("没有设定");
    }

    @Test
    void checkLimits() {
        assertThat(StorageCondition.checkLimits(limits(null, null, null, null))).contains("至少设定");
        assertThat(StorageCondition.checkLimits(limits("30", "30", null, null))).isEqualTo("温度下限要低于上限");
        assertThat(StorageCondition.checkLimits(limits(null, null, "80", "40"))).isEqualTo("湿度下限要低于上限");
        assertThat(StorageCondition.checkLimits(limits(null, "25", null, null))).isNull();
    }

    @Test
    void boundariesAreInRangeAndUnsetLimitsAreNotJudged() {
        StorageCondition.Limits l = limits("15", "30", null, "75");
        assertThat(StorageCondition.violations(l, d("30"), d("75"))).isEmpty();
        assertThat(StorageCondition.violations(l, d("15.0"), null)).isEmpty();
        assertThat(StorageCondition.violations(l, d("30.1"), d("75.5"))).containsExactly("温度 30.1℃ 高于上限 30℃", "湿度 75.5% 高于上限 75%");
        assertThat(StorageCondition.violations(l, d("14.9"), d("5"))).containsExactly("温度 14.9℃ 低于下限 15℃");
    }

    @Test
    void readingsForLimitedParametersAreRequired() {
        StorageCondition.Limits both = limits(null, "30", null, "75");
        assertThat(StorageCondition.missingReading(both, null, null)).isEqualTo("请填写温度或湿度");
        assertThat(StorageCondition.missingReading(both, d("20"), null)).contains("请填写相对湿度");
        assertThat(StorageCondition.missingReading(both, null, d("50"))).contains("请填写温度");
        assertThat(StorageCondition.missingReading(limits(null, "30", null, null), d("20"), null)).isNull();
        assertThat(StorageCondition.missingReading(limits(null, "30", null, null), d("20"), d("50"))).isNull();
    }

    @Test
    void excursionsMergeWithinTheWindowOfFirstOrLastReading() {
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 1, 8, 0);
        Duration w = Duration.ofHours(24);
        List<StorageCondition.Window> windows = new ArrayList<>();
        windows.add(new StorageCondition.Window(t0, t0.plusHours(6), true));
        List<Integer> a = StorageCondition.assign(windows, List.of(
                t0.minusHours(20),          // 第一条之前 20 小时：并进已有的，开始时间提前
                t0.plusHours(29),           // 最后一条之后 23 小时：并进
                t0.plusHours(60),           // 最后一条（29h）之后 31 小时：新的一次
                t0.plusHours(70)), w);      // 并进刚才新建的
        assertThat(a).containsExactly(0, 0, 1, 1);
        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).start()).isEqualTo(t0.minusHours(20));
        assertThat(windows.get(0).last()).isEqualTo(t0.plusHours(29));
        assertThat(windows.get(1).existing()).isFalse();
        assertThat(windows.get(1).last()).isEqualTo(t0.plusHours(70));
    }

    @Test
    void missingDaysAndText() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        List<LocalDate> missing = StorageCondition.missingDays(from, from.plusDays(6), Set.of(from, from.plusDays(1), from.plusDays(5)));
        assertThat(missing).containsExactly(from.plusDays(2), from.plusDays(3), from.plusDays(4), from.plusDays(6));
        assertThat(StorageCondition.daysText(missing, 5)).isEqualTo("2026-11-03 至 2026-11-05、2026-11-07");
        assertThat(StorageCondition.daysText(missing, 1)).isEqualTo("2026-11-03 至 2026-11-05 等 2 段");
        assertThat(StorageCondition.missingDays(from, from.minusDays(1), Set.of())).isEmpty();
    }
}
