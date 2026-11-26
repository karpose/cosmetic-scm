package com.cosmetic.scm.domain.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.cosmetic.scm.domain.FindingSeverity;
import com.cosmetic.scm.domain.SupervisionConclusion;

/**
 * 受托生产监督的规则（检查要点委托生产版第 9 项）：发现的问题怎样才算处理了、结论怎么算、受托厂什么时候该再去监督。
 * 不依赖框架，有单元测试。
 */
public final class SupervisionRule {

    /** corrected：写了当场纠正的情况；hasCapa：已经转了 CAPA。 */
    public record Finding(int seq, FindingSeverity severity, boolean corrected, boolean hasCapa) {
    }

    private SupervisionRule() {
    }

    /** 重大问题必须转 CAPA；一般问题当场纠正或转 CAPA 都可以。 */
    public static boolean handled(Finding f) {
        return f.hasCapa() || (f.severity() == FindingSeverity.MINOR && f.corrected());
    }

    /** 提交前还没处理的问题，每条一句提示；都处理了返回空。 */
    public static List<String> unhandled(Collection<Finding> list) {
        List<String> out = new ArrayList<>();
        for (Finding f : list) {
            if (handled(f)) {
                continue;
            }
            out.add(f.severity() == FindingSeverity.MAJOR ? "问题 " + f.seq() + " 是重大问题，要转 CAPA"
                    : "问题 " + f.seq() + " 还没有写当场纠正的情况，也没有转 CAPA");
        }
        return out;
    }

    /** 有重大问题算“发现重大问题”，只有一般问题算“发现一般问题”，没有问题算“未发现问题”。 */
    public static SupervisionConclusion conclusion(Collection<FindingSeverity> severities) {
        if (severities.contains(FindingSeverity.MAJOR)) {
            return SupervisionConclusion.MAJOR;
        }
        return severities.isEmpty() ? SupervisionConclusion.NONE : SupervisionConclusion.MINOR;
    }

    /**
     * 受托厂该不该再去监督：有还没完成的委托单，而且从来没有监督过、或最近一次监督已超过 intervalDays 天。
     * 没有在做的单子不算到期。
     */
    public static boolean due(int openOrders, LocalDate lastSupervised, LocalDate today, int intervalDays) {
        if (openOrders <= 0) {
            return false;
        }
        return lastSupervised == null || lastSupervised.plusDays(intervalDays).isBefore(today);
    }
}
