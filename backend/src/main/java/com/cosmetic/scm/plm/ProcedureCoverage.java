package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.domain.DocStatus;
import com.cosmetic.scm.domain.QualityProcedure;
import com.cosmetic.scm.domain.RevisionStatus;

/**
 * 制度覆盖：每项制度有没有对应的在用受控文件。不依赖 Spring，受控文件和 GMP 自查都用它算，服务之间不互相注入。
 * 有生效版本的在用文件 = 已建立；只有没生效的版本（待签批、待生效）= 在建；都没有 = 缺。作废的文件不算。
 */
public final class ProcedureCoverage {

    public enum State {
        ESTABLISHED("已建立"),
        DRAFTING("在建"),
        MISSING("缺");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public record DocRef(Long id, String docCode, String title, Integer currentRev) {
    }

    public record Row(QualityProcedure code, String label, List<Integer> items, boolean optional, State state, String stateLabel, List<DocRef> docs) {
    }

    private ProcedureCoverage() {
    }

    public static Set<QualityProcedure> parse(String s) {
        if (s == null || s.isBlank()) {
            return EnumSet.noneOf(QualityProcedure.class);
        }
        return Arrays.stream(s.split(",")).filter(x -> !x.isBlank()).map(QualityProcedure::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(QualityProcedure.class)));
    }

    public static String join(Collection<QualityProcedure> set) {
        if (set == null || set.isEmpty()) {
            return null;
        }
        return set.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    public static String labels(Collection<QualityProcedure> set) {
        return set == null || set.isEmpty() ? "无" : set.stream().sorted().map(QualityProcedure::getLabel).collect(Collectors.joining("、"));
    }

    /** 请求里的代码转成枚举，认不出的报错。 */
    public static Set<QualityProcedure> of(Collection<String> codes) {
        Set<QualityProcedure> out = EnumSet.noneOf(QualityProcedure.class);
        if (codes == null) {
            return out;
        }
        for (String c : codes) {
            try {
                out.add(QualityProcedure.valueOf(c));
            } catch (IllegalArgumentException e) {
                throw new BizException("不认识的制度：" + c);
            }
        }
        return out;
    }

    /** revisionsByDoc：每份文件的全部版本。 */
    public static List<Row> compute(List<ControlledDoc> docs, Map<Long, List<DocRevision>> revisionsByDoc, LocalDate today) {
        List<Row> out = new ArrayList<>();
        for (QualityProcedure p : QualityProcedure.values()) {
            List<DocRef> established = new ArrayList<>();
            boolean drafting = false;
            for (ControlledDoc d : docs) {
                if (d.getStatus() != DocStatus.ACTIVE || !parse(d.getProcedures()).contains(p)) {
                    continue;
                }
                List<DocRevision> revs = revisionsByDoc.getOrDefault(d.getId(), List.of());
                Integer current = revs.stream().filter(r -> r.getStatus() == RevisionStatus.APPROVED && !r.getEffectiveDate().isAfter(today))
                        .map(DocRevision::getRevNo).max(Comparator.naturalOrder()).orElse(null);
                if (current != null) {
                    established.add(new DocRef(d.getId(), d.getDocCode(), d.getTitle(), current));
                } else {
                    drafting = true;
                }
            }
            State state = !established.isEmpty() ? State.ESTABLISHED : drafting ? State.DRAFTING : State.MISSING;
            out.add(new Row(p, p.getLabel(), p.getItems(), p.isOptional(), state, state.getLabel(), established));
        }
        return out;
    }
}
