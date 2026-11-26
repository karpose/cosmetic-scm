package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import com.cosmetic.scm.domain.FindingSeverity;
import com.cosmetic.scm.domain.SupervisionConclusion;

import org.junit.jupiter.api.Test;

class SupervisionRuleTest {

    private static final FindingSeverity MINOR = FindingSeverity.MINOR;
    private static final FindingSeverity MAJOR = FindingSeverity.MAJOR;

    @Test
    void majorNeedsCapaMinorCanBeCorrectedOnSite() {
        assertThat(SupervisionRule.handled(new SupervisionRule.Finding(1, MINOR, true, false))).isTrue();
        assertThat(SupervisionRule.handled(new SupervisionRule.Finding(1, MINOR, false, true))).isTrue();
        assertThat(SupervisionRule.handled(new SupervisionRule.Finding(1, MINOR, false, false))).isFalse();
        // 重大问题写了当场纠正也不够
        assertThat(SupervisionRule.handled(new SupervisionRule.Finding(2, MAJOR, true, false))).isFalse();
        assertThat(SupervisionRule.handled(new SupervisionRule.Finding(2, MAJOR, false, true))).isTrue();

        List<String> msgs = SupervisionRule.unhandled(List.of(new SupervisionRule.Finding(1, MINOR, false, false),
                new SupervisionRule.Finding(2, MAJOR, true, false), new SupervisionRule.Finding(3, MINOR, true, false)));
        assertThat(msgs).containsExactly("问题 1 还没有写当场纠正的情况，也没有转 CAPA", "问题 2 是重大问题，要转 CAPA");
        assertThat(SupervisionRule.unhandled(List.of())).isEmpty();
    }

    @Test
    void conclusionTakesTheWorstFinding() {
        assertThat(SupervisionRule.conclusion(List.of())).isEqualTo(SupervisionConclusion.NONE);
        assertThat(SupervisionRule.conclusion(List.of(MINOR, MINOR))).isEqualTo(SupervisionConclusion.MINOR);
        assertThat(SupervisionRule.conclusion(List.of(MINOR, MAJOR))).isEqualTo(SupervisionConclusion.MAJOR);
    }

    @Test
    void dueOnlyWithOpenOrdersAndNoRecentSupervision() {
        LocalDate today = LocalDate.of(2026, 11, 26);
        assertThat(SupervisionRule.due(0, null, today, 90)).isFalse();
        assertThat(SupervisionRule.due(2, null, today, 90)).isTrue();
        // 正好 90 天前还不算到期，91 天前算
        assertThat(SupervisionRule.due(1, today.minusDays(90), today, 90)).isFalse();
        assertThat(SupervisionRule.due(1, today.minusDays(91), today, 90)).isTrue();
        assertThat(SupervisionRule.due(1, today, today, 90)).isFalse();
    }
}
