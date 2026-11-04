package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 比较两个配方版本。原料的增减或用量变化等于产品配方变了，须先完成备案变更才能生效；
 * 包材变化、供料方式变化不涉及配方成分。
 */
public final class FormulaDiff {

    private FormulaDiff() {
    }

    public enum Kind {
        ADDED, REMOVED, QTY_CHANGED, SUPPLY_CHANGED
    }

    public record Line(Long itemId, BigDecimal qtyPer, String supplyMode, boolean rawMaterial) {
    }

    public record Change(Long itemId, Kind kind, BigDecimal oldQty, BigDecimal newQty, boolean rawMaterial) {

        /** 这一处变化是否改变了产品配方成分。 */
        public boolean affectsFormula() {
            return rawMaterial && kind != Kind.SUPPLY_CHANGED;
        }
    }

    public static List<Change> diff(List<Line> before, List<Line> after) {
        Map<Long, Line> old = new LinkedHashMap<>();
        before.forEach(l -> old.put(l.itemId(), l));
        List<Change> out = new ArrayList<>();
        for (Line now : after) {
            Line was = old.remove(now.itemId());
            if (was == null) {
                out.add(new Change(now.itemId(), Kind.ADDED, null, now.qtyPer(), now.rawMaterial()));
            } else if (was.qtyPer().compareTo(now.qtyPer()) != 0) {
                out.add(new Change(now.itemId(), Kind.QTY_CHANGED, was.qtyPer(), now.qtyPer(), now.rawMaterial()));
            } else if (!was.supplyMode().equals(now.supplyMode())) {
                out.add(new Change(now.itemId(), Kind.SUPPLY_CHANGED, was.qtyPer(), now.qtyPer(), now.rawMaterial()));
            }
        }
        old.values().forEach(l -> out.add(new Change(l.itemId(), Kind.REMOVED, l.qtyPer(), null, l.rawMaterial())));
        return out;
    }

    public static boolean needsFiling(List<Change> changes) {
        return changes.stream().anyMatch(Change::affectsFormula);
    }
}
