package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class ShelfLifeAndFormulaTest {

    @Test
    void shelfLifeRatioAndDates() {
        LocalDate today = LocalDate.of(2026, 11, 2);
        assertThat(ShelfLife.remainingRatio(LocalDate.of(2024, 2, 15), LocalDate.of(2027, 2, 14), today)).isLessThan(0.34);
        assertThat(ShelfLife.remainingRatio(null, LocalDate.of(2027, 1, 1), today)).isNull();
        assertThat(ShelfLife.expiryFrom(LocalDate.of(2026, 10, 20), 36)).isEqualTo(LocalDate.of(2029, 10, 20));
        assertThat(ShelfLife.retainUntil(LocalDate.of(2029, 10, 20))).isEqualTo(LocalDate.of(2030, 4, 20));
        assertThat(ShelfLife.isExpired(LocalDate.of(2026, 11, 2), today)).isFalse();
        assertThat(ShelfLife.isExpired(LocalDate.of(2026, 11, 1), today)).isTrue();
    }

    @Test
    void monthEndAndLeapDayBoundaries() {
        // 目标月没有这一天时落到月末，不会顺延到下个月
        assertThat(ShelfLife.expiryFrom(LocalDate.of(2026, 8, 31), 6)).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(ShelfLife.expiryFrom(LocalDate.of(2028, 2, 29), 12)).isEqualTo(LocalDate.of(2029, 2, 28));
        assertThat(ShelfLife.retainUntil(LocalDate.of(2027, 8, 31))).isEqualTo(LocalDate.of(2028, 2, 29));
        // 有效期当天仍可用，剩余比例为 0；过期后也不会是负数
        LocalDate exp = LocalDate.of(2028, 2, 29);
        assertThat(ShelfLife.isExpired(exp, exp)).isFalse();
        assertThat(ShelfLife.isExpired(exp, exp.plusDays(1))).isTrue();
        assertThat(ShelfLife.remainingRatio(LocalDate.of(2026, 2, 28), exp, exp)).isZero();
        assertThat(ShelfLife.remainingRatio(LocalDate.of(2026, 2, 28), exp, exp.plusDays(30))).isZero();
    }

    @Test
    void restrictedIngredientConcentration() {
        FormulaCompliance.Check ok = FormulaCompliance.check("苯氧乙醇", true, false, new BigDecimal("1.0"),
                new BigDecimal("0.0004"), "kg", new BigDecimal("50"));
        assertThat(ok.level()).isEqualTo(FormulaCompliance.Level.OK);
        assertThat(ok.concentrationPct()).isEqualByComparingTo("0.8");

        FormulaCompliance.Check over = FormulaCompliance.check("苯氧乙醇", true, false, new BigDecimal("1.0"),
                new BigDecimal("0.0006"), "kg", new BigDecimal("50"));
        assertThat(over.blocking()).isTrue();
    }

    @Test
    void bannedAndPackaging() {
        assertThat(FormulaCompliance.check("x", true, true, null, BigDecimal.ONE, "kg", BigDecimal.TEN).level())
                .isEqualTo(FormulaCompliance.Level.BANNED);
        assertThat(FormulaCompliance.check("瓶", false, false, null, BigDecimal.ONE, "个", BigDecimal.TEN).level())
                .isEqualTo(FormulaCompliance.Level.NOT_APPLICABLE);
    }
}
