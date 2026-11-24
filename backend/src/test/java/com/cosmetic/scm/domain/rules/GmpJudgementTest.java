package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.cosmetic.scm.domain.CheckLevel;
import com.cosmetic.scm.domain.SelfCheckConclusion;
import com.cosmetic.scm.domain.SelfCheckResult;
import com.cosmetic.scm.domain.rules.GmpJudgement.Line;

import org.junit.jupiter.api.Test;

/** 判定原则的边界：关键项目不符合、关键瑕疵 + 其他重点不符合 ≥ 4、三类合计 ≥ 8。 */
class GmpJudgementTest {

    private static final CheckLevel K = CheckLevel.KEY;
    private static final CheckLevel I = CheckLevel.IMPORTANT;
    private static final CheckLevel G = CheckLevel.GENERAL;

    /** 委托生产版的结构：关键 1、其他重点 8、一般 15，默认都符合。 */
    private static List<Line> base() {
        List<Line> l = new ArrayList<>();
        l.add(new Line(K, SelfCheckResult.COMPLIANT));
        for (int i = 0; i < 8; i++) {
            l.add(new Line(I, SelfCheckResult.COMPLIANT));
        }
        for (int i = 0; i < 15; i++) {
            l.add(new Line(G, SelfCheckResult.COMPLIANT));
        }
        return l;
    }

    private static List<Line> with(List<Line> l, int from, int n, SelfCheckResult r) {
        for (int i = from; i < from + n; i++) {
            l.set(i, new Line(l.get(i).level(), r));
        }
        return l;
    }

    @Test
    void allCompliantOrNotApplicableHasNoDefect() {
        assertThat(GmpJudgement.judge(base())).isEqualTo(SelfCheckConclusion.NO_DEFECT);
        assertThat(GmpJudgement.judge(with(base(), 9, 3, SelfCheckResult.NOT_APPLICABLE))).isEqualTo(SelfCheckConclusion.NO_DEFECT);
    }

    @Test
    void anyKeyNoncomplianceIsSevere() {
        assertThat(GmpJudgement.judge(with(base(), 0, 1, SelfCheckResult.NONCOMPLIANT))).isEqualTo(SelfCheckConclusion.SEVERE);
    }

    @Test
    void keyFlawPlusImportantNoncomplianceReachingFourIsSevere() {
        // 关键瑕疵 1 + 其他重点不符合 2 = 3：存在缺陷
        List<Line> three = with(with(base(), 0, 1, SelfCheckResult.FLAWED), 1, 2, SelfCheckResult.NONCOMPLIANT);
        assertThat(GmpJudgement.judge(three)).isEqualTo(SelfCheckConclusion.DEFECT);
        assertThat(GmpJudgement.count(three).rule2()).isEqualTo(3);
        // 再加一项其他重点不符合 = 4：严重缺陷
        assertThat(GmpJudgement.judge(with(three, 3, 1, SelfCheckResult.NONCOMPLIANT))).isEqualTo(SelfCheckConclusion.SEVERE);
        // 其他重点不符合 4 项（没有关键瑕疵）也是 4
        assertThat(GmpJudgement.judge(with(base(), 1, 4, SelfCheckResult.NONCOMPLIANT))).isEqualTo(SelfCheckConclusion.SEVERE);
    }

    @Test
    void sumOfImportantAndGeneralFindingsReachingEightIsSevere() {
        // 其他重点瑕疵 3 + 一般不符合 4 = 7：存在缺陷；一般项目再多一项不符合 = 8：严重缺陷
        List<Line> seven = with(with(base(), 1, 3, SelfCheckResult.FLAWED), 9, 4, SelfCheckResult.NONCOMPLIANT);
        assertThat(GmpJudgement.count(seven).rule3()).isEqualTo(7);
        assertThat(GmpJudgement.judge(seven)).isEqualTo(SelfCheckConclusion.DEFECT);
        assertThat(GmpJudgement.judge(with(seven, 13, 1, SelfCheckResult.NONCOMPLIANT))).isEqualTo(SelfCheckConclusion.SEVERE);
    }

    @Test
    void onlyFlawsStillCountAsDefect() {
        assertThat(GmpJudgement.judge(with(base(), 1, 1, SelfCheckResult.FLAWED))).isEqualTo(SelfCheckConclusion.DEFECT);
    }
}
