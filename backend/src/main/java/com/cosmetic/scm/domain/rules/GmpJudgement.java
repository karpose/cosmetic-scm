package com.cosmetic.scm.domain.rules;

import java.util.List;

import com.cosmetic.scm.domain.CheckLevel;
import com.cosmetic.scm.domain.SelfCheckConclusion;
import com.cosmetic.scm.domain.SelfCheckResult;

/**
 * 《化妆品生产质量管理规范检查要点及判定原则》的判定：
 * 关键项目不符合；或关键项目瑕疵数加其他重点项目不符合数 ≥ 4；或重点项目（含关键）不符合数、重点项目瑕疵数、一般项目不符合数之和 ≥ 8，
 * 为“存在严重缺陷”。有不符合或瑕疵、但没到上面的情形，为“存在缺陷”；都符合（或不适用）为“未发现缺陷”。
 * 原文只说“有不符合规定项目”为存在缺陷，这里把瑕疵也算进去（偏严），是默认规则。
 */
public final class GmpJudgement {

    public record Line(CheckLevel level, SelfCheckResult result) {
    }

    public record Counts(int keyNoncompliant, int keyFlawed, int importantNoncompliant, int importantFlawed, int generalNoncompliant) {

        /** 关键项目瑕疵数 + 其他重点项目不符合数。 */
        public int rule2() {
            return keyFlawed + importantNoncompliant;
        }

        /** 重点项目（含关键）不符合 + 重点项目（含关键）瑕疵 + 一般项目不符合。 */
        public int rule3() {
            return keyNoncompliant + importantNoncompliant + keyFlawed + importantFlawed + generalNoncompliant;
        }
    }

    public static final int RULE2_LIMIT = 4;
    public static final int RULE3_LIMIT = 8;

    private GmpJudgement() {
    }

    public static Counts count(List<Line> lines) {
        int kn = 0;
        int kf = 0;
        int in = 0;
        int iflaw = 0;
        int gn = 0;
        for (Line l : lines) {
            boolean non = l.result() == SelfCheckResult.NONCOMPLIANT;
            boolean flaw = l.result() == SelfCheckResult.FLAWED;
            switch (l.level()) {
                case KEY -> {
                    kn += non ? 1 : 0;
                    kf += flaw ? 1 : 0;
                }
                case IMPORTANT -> {
                    in += non ? 1 : 0;
                    iflaw += flaw ? 1 : 0;
                }
                case GENERAL -> gn += non ? 1 : 0;
                default -> throw new IllegalStateException(String.valueOf(l.level()));
            }
        }
        return new Counts(kn, kf, in, iflaw, gn);
    }

    public static SelfCheckConclusion judge(List<Line> lines) {
        Counts c = count(lines);
        if (c.keyNoncompliant() > 0 || c.rule2() >= RULE2_LIMIT || c.rule3() >= RULE3_LIMIT) {
            return SelfCheckConclusion.SEVERE;
        }
        boolean any = lines.stream().anyMatch(l -> l.result() == SelfCheckResult.NONCOMPLIANT || l.result() == SelfCheckResult.FLAWED);
        return any ? SelfCheckConclusion.DEFECT : SelfCheckConclusion.NO_DEFECT;
    }
}
