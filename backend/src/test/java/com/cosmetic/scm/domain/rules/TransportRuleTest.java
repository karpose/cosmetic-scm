package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class TransportRuleTest {

    private static BigDecimal d(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static TransportRule.Requirement req(String name, String min, String max, boolean monitor, String note) {
        return new TransportRule.Requirement(name, d(min), d(max), monitor, note);
    }

    @Test
    void requirementNeedsRangeOrNoteAndMonitoringNeedsRange() {
        assertThat(TransportRule.checkRequirement(null, null, false, " ")).contains("温度范围或填写运输注意事项");
        assertThat(TransportRule.checkRequirement(null, null, false, "防压")).isNull();
        assertThat(TransportRule.checkRequirement(d("30"), d("5"), false, null)).contains("下限要低于上限");
        assertThat(TransportRule.checkRequirement(d("5"), d("5"), false, null)).contains("下限要低于上限");
        assertThat(TransportRule.checkRequirement(null, null, true, "防压")).contains("请设定温度范围");
        assertThat(TransportRule.checkRequirement(null, d("30"), true, null)).isNull();
    }

    @Test
    void combineTakesTheStrictestRangeAndKeepsNotes() {
        TransportRule.Combined c = TransportRule.combine(List.of(req("精华液", "5", "30", false, "避光"), req("面霜", "2", "25", true, "防冻"),
                req("面膜", null, null, false, "避光")));
        assertThat(c.tempMin()).isEqualByComparingTo("5");
        assertThat(c.tempMax()).isEqualByComparingTo("25");
        assertThat(c.monitor()).isTrue();
        assertThat(c.conflict()).isNull();
        assertThat(c.text()).isEqualTo("5–25℃，记录途中温度；避光；防冻");
        assertThat(TransportRule.combine(List.of()).text()).isEqualTo("没有设定");
        assertThat(TransportRule.combine(List.of(req("A", null, "8", false, null))).text()).isEqualTo("≤8℃");

        TransportRule.Combined bad = TransportRule.combine(List.of(req("冷藏精华", "2", "8", false, null), req("防冻乳液", "10", null, false, null)));
        assertThat(bad.conflict()).isEqualTo("同一次运输的产品温度要求不相容：防冻乳液 要求不低于 10℃，冷藏精华 要求不高于 8℃");
        assertThat(TransportRule.problems(bad, false, true, d("9"), d("9"), false, null)).contains(bad.conflict());
    }

    @Test
    void readingsOnTheLimitConformAndOutsideDoNot() {
        TransportRule.Combined c = TransportRule.combine(List.of(req("精华液", "5", "30", true, null)));
        assertThat(TransportRule.problems(c, false, true, d("5"), d("30"), false, null)).isEmpty();
        assertThat(TransportRule.problems(c, false, true, d("4.9"), d("30.1"), false, null))
                .containsExactly("最高温度 30.1℃ 高于上限 30℃", "最低温度 4.9℃ 低于下限 5℃");
        // 要求记录却没记录；客户自提不要求
        assertThat(TransportRule.problems(c, false, false, null, null, false, null)).containsExactly("要求记录途中温度，但没有记录");
        assertThat(TransportRule.problems(c, true, false, null, null, false, null)).isEmpty();
        // 到货异常
        assertThat(TransportRule.problems(c, true, false, null, null, true, "外箱压坏 2 箱")).containsExactly("到货异常：外箱压坏 2 箱");
        // 没有要求时，有温度也不判断范围
        assertThat(TransportRule.problems(TransportRule.combine(List.of()), false, true, d("40"), d("45"), false, null)).isEmpty();
    }

    @Test
    void readingInputChecks() {
        assertThat(TransportRule.checkReading(false, null, null)).isNull();
        assertThat(TransportRule.checkReading(false, d("20"), null)).contains("请不要填写温度");
        assertThat(TransportRule.checkReading(true, d("20"), null)).contains("最低和最高温度");
        assertThat(TransportRule.checkReading(true, d("25"), d("20"))).contains("最低温度不能高于最高温度");
        assertThat(TransportRule.reading(true, d("18.50"), d("24.0"))).isEqualTo("18.5–24℃");
        assertThat(TransportRule.reading(true, d("20"), d("20.0"))).isEqualTo("20℃");
        assertThat(TransportRule.reading(false, null, null)).isEqualTo("没有记录温度");
    }
}
