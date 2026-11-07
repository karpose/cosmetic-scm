package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class SpecLimitTest {

    private static BigDecimal d(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    @Test
    void boundsAreInclusiveAndOptional() {
        assertThat(SpecLimit.within(d("5"), d("7"), d("5.0000"))).isTrue();
        assertThat(SpecLimit.within(d("5"), d("7"), d("7"))).isTrue();
        assertThat(SpecLimit.within(d("5"), d("7"), d("7.0001"))).isFalse();
        assertThat(SpecLimit.within(d("5"), d("7"), d("4.9999"))).isFalse();
        assertThat(SpecLimit.within(null, d("100"), d("-1"))).isTrue();
        assertThat(SpecLimit.within(d("98"), null, d("97.99"))).isFalse();
        assertThat(SpecLimit.hasLimits(null, null)).isFalse();
    }

    @Test
    void textShowsTheLimitsPlainly() {
        assertThat(SpecLimit.text(d("5.00"), d("7.0"), null)).isEqualTo("5 ~ 7");
        assertThat(SpecLimit.text(d("98"), null, "%")).isEqualTo("≥ 98 %");
        assertThat(SpecLimit.text(null, d("100"), " cfu/g ")).isEqualTo("≤ 100 cfu/g");
        assertThat(SpecLimit.text(null, null, "%")).isEmpty();
    }
}
