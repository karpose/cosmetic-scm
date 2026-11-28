package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.cosmetic.scm.domain.RetainSiteKind;
import com.cosmetic.scm.domain.rules.RetainSiteRule.State;

class RetainSiteRuleTest {

    private static final RetainSiteKind OTHER = RetainSiteKind.OTHER_PREMISES;

    @Test
    void workingDaysSkipWeekendsAndStartTheDayAfter() {
        LocalDate fri = LocalDate.of(2026, 11, 27);
        assertThat(RetainSiteRule.addWorkingDays(fri, 1)).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(RetainSiteRule.addWorkingDays(LocalDate.of(2026, 11, 28), 1)).isEqualTo(LocalDate.of(2026, 11, 30));
        // 20 个工作日正好 4 周
        assertThat(RetainSiteRule.addWorkingDays(LocalDate.of(2026, 11, 4), 20)).isEqualTo(LocalDate.of(2026, 12, 2));
        assertThat(RetainSiteRule.deadline(OTHER, LocalDate.of(2026, 11, 4))).isEqualTo(LocalDate.of(2026, 12, 2));
        assertThat(RetainSiteRule.deadline(RetainSiteKind.DOMICILE, LocalDate.of(2026, 11, 4))).isNull();
        assertThat(RetainSiteRule.deadline(OTHER, null)).isNull();
    }

    @Test
    void states() {
        LocalDate first = LocalDate.of(2026, 11, 4);
        LocalDate deadline = LocalDate.of(2026, 12, 2);
        assertThat(RetainSiteRule.state(RetainSiteKind.DOMICILE, false, first, null, deadline.plusDays(30))).isEqualTo(State.NOT_REQUIRED);
        assertThat(RetainSiteRule.state(RetainSiteKind.MAIN_PREMISES, true, first, null, deadline.plusDays(30))).isEqualTo(State.NOT_REQUIRED);
        assertThat(RetainSiteRule.state(OTHER, false, first, first, first)).isEqualTo(State.NOT_ALLOWED);
        assertThat(RetainSiteRule.state(OTHER, true, null, null, first)).isEqualTo(State.NOT_USED);
        // 截止日当天还算按期
        assertThat(RetainSiteRule.state(OTHER, true, first, null, deadline)).isEqualTo(State.PENDING);
        assertThat(RetainSiteRule.state(OTHER, true, first, null, deadline.plusDays(1))).isEqualTo(State.OVERDUE);
        assertThat(RetainSiteRule.state(OTHER, true, first, deadline, deadline.plusDays(5))).isEqualTo(State.REPORTED);
        assertThat(RetainSiteRule.state(OTHER, true, first, deadline.plusDays(1), deadline.plusDays(5))).isEqualTo(State.REPORTED_LATE);
        // 先报告、后留样
        assertThat(RetainSiteRule.state(OTHER, true, null, first, first)).isEqualTo(State.REPORTED);
        // 看期间结束时：之后才报告的当作没报告，之后才留样的当作没留样
        assertThat(RetainSiteRule.state(OTHER, true, first, deadline, first.plusDays(3))).isEqualTo(State.PENDING);
        assertThat(RetainSiteRule.state(OTHER, true, first, null, first.minusDays(1))).isEqualTo(State.NOT_USED);
        assertThat(State.OVERDUE.problem()).isTrue();
        assertThat(State.PENDING.problem()).isFalse();
        assertThat(RetainSiteRule.earliest(first, null)).isEqualTo(first);
        assertThat(RetainSiteRule.earliest(first, first.minusDays(2))).isEqualTo(first.minusDays(2));
        assertThat(RetainSiteRule.earliest(null, null)).isNull();
    }
}
