package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ReplenishmentTest {

    private static final Replenishment.Params P = new Replenishment.Params(90, 30, 15, 60);

    @Test
    void suggestsTopUpWhenBelowReorderPointRoundedToLot() {
        // 90 天卖 9000 → 日均 100；安全 1500；再订货点 3000 + 1500 = 4500
        Replenishment.Result r = Replenishment.calc(new BigDecimal("9000"), new BigDecimal("2000"), new BigDecimal("1000"),
                P, new BigDecimal("500"));
        assertThat(r.dailyDemand()).isEqualByComparingTo("100");
        assertThat(r.reorderPoint()).isEqualByComparingTo("4500");
        assertThat(r.belowReorderPoint()).isTrue();
        assertThat(r.daysOfSupply()).isEqualTo(30);
        // 目标 4500 + 6000 = 10500，缺 7500，按 500 取整仍为 7500
        assertThat(r.suggested()).isEqualByComparingTo("7500");
    }

    @Test
    void noSuggestionWhenEnoughOrNoDemand() {
        assertThat(Replenishment.calc(new BigDecimal("9000"), new BigDecimal("5000"), BigDecimal.ZERO, P, null).suggested())
                .isZero();
        Replenishment.Result none = Replenishment.calc(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, P, null);
        assertThat(none.suggested()).isZero();
        assertThat(none.daysOfSupply()).isNull();
    }

    @Test
    void netRequirementRoundsUpToLot() {
        assertThat(Replenishment.netRequirement(new BigDecimal("12.3"), new BigDecimal("2"), new BigDecimal("5")))
                .isEqualByComparingTo("15");
        assertThat(Replenishment.netRequirement(new BigDecimal("3"), new BigDecimal("8"), null)).isZero();
    }
}
