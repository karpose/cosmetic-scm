package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 产品运输条件的判断（GMP 第五十九条：依照法规和产品标签标示的要求贮存、运输产品；检查要点委托生产版序号 20）。
 * 运输条件不内置，由质量部门给成品设定：温度范围（可只设上限或下限）、途中是否要记录温度、注意事项。
 * 一次运输有多个产品时取最严的范围（下限取最高的、上限取最低的）；任一产品要求记录温度就要记录。
 * 判定：读数等于上下限算符合；最低、最高温度超出范围，要求记录却没有记录（客户自提除外），到货破损，都算不符合。
 */
public final class TransportRule {

    private TransportRule() {
    }

    /** 一个产品的运输条件。 */
    public record Requirement(String itemName, BigDecimal tempMin, BigDecimal tempMax, boolean monitor, String note) {
    }

    /** 合并后的条件。conflict：下限高于上限时的说明（同一次运输的产品温度要求不相容），否则 null。 */
    public record Combined(BigDecimal tempMin, BigDecimal tempMax, boolean monitor, String text, String conflict) {

        public boolean hasTemp() {
            return tempMin != null || tempMax != null;
        }
    }

    /** 设定条件时的检查，没有问题返回 null。 */
    public static String checkRequirement(BigDecimal tempMin, BigDecimal tempMax, boolean monitor, String note) {
        boolean hasTemp = tempMin != null || tempMax != null;
        if (!hasTemp && (note == null || note.isBlank())) {
            return "请设定温度范围或填写运输注意事项";
        }
        if (tempMin != null && tempMax != null && tempMin.compareTo(tempMax) >= 0) {
            return "温度下限要低于上限";
        }
        if (monitor && !hasTemp) {
            return "要求记录途中温度时，请设定温度范围";
        }
        return null;
    }

    /** 例如“2–8℃”“≤30℃”“≥5℃”。 */
    public static String range(BigDecimal min, BigDecimal max) {
        if (min != null && max != null) {
            return plain(min) + "–" + plain(max) + "℃";
        }
        if (max != null) {
            return "≤" + plain(max) + "℃";
        }
        return min == null ? "" : "≥" + plain(min) + "℃";
    }

    /** 一个产品条件的文字，例如“5–30℃，记录途中温度；避光、防冻”。 */
    public static String text(BigDecimal min, BigDecimal max, boolean monitor, String note) {
        List<String> parts = new ArrayList<>();
        if (min != null || max != null) {
            parts.add(range(min, max) + (monitor ? "，记录途中温度" : ""));
        }
        if (note != null && !note.isBlank()) {
            parts.add(note.trim());
        }
        return parts.isEmpty() ? "没有设定" : String.join("；", parts);
    }

    public static Combined combine(List<Requirement> reqs) {
        BigDecimal min = null;
        BigDecimal max = null;
        String minOf = null;
        String maxOf = null;
        boolean monitor = false;
        Set<String> notes = new LinkedHashSet<>();
        for (Requirement r : reqs) {
            if (r.tempMin() != null && (min == null || r.tempMin().compareTo(min) > 0)) {
                min = r.tempMin();
                minOf = r.itemName();
            }
            if (r.tempMax() != null && (max == null || r.tempMax().compareTo(max) < 0)) {
                max = r.tempMax();
                maxOf = r.itemName();
            }
            monitor |= r.monitor();
            if (r.note() != null && !r.note().isBlank()) {
                notes.add(r.note().trim());
            }
        }
        String conflict = min != null && max != null && min.compareTo(max) > 0
                ? "同一次运输的产品温度要求不相容：" + minOf + " 要求不低于 " + plain(min) + "℃，" + maxOf + " 要求不高于 " + plain(max) + "℃"
                : null;
        List<String> parts = new ArrayList<>();
        if (min != null || max != null) {
            parts.add((conflict == null ? range(min, max) : "温度要求不相容") + (monitor ? "，记录途中温度" : ""));
        }
        parts.addAll(notes);
        return new Combined(min, max, monitor, parts.isEmpty() ? "没有设定" : String.join("；", parts), conflict);
    }

    /** 录入时的检查：记录了温度就要填最低和最高，最低不能高于最高。没有问题返回 null。 */
    public static String checkReading(boolean monitored, BigDecimal low, BigDecimal high) {
        if (!monitored) {
            return low != null || high != null ? "没有勾选“途中记录了温度”时，请不要填写温度" : null;
        }
        if (low == null || high == null) {
            return "请填写途中的最低和最高温度";
        }
        return low.compareTo(high) > 0 ? "最低温度不能高于最高温度" : null;
    }

    /** 不符合的说明，符合返回空列表。pickup：客户自提，运输由客户负责，不要求记录温度（记录了照样判断）。 */
    public static List<String> problems(Combined c, boolean pickup, boolean monitored, BigDecimal low, BigDecimal high, boolean damaged, String damageNote) {
        List<String> out = new ArrayList<>();
        if (c.conflict() != null) {
            out.add(c.conflict());
        }
        if (monitored) {
            if (c.tempMax() != null && high != null && high.compareTo(c.tempMax()) > 0) {
                out.add("最高温度 " + plain(high) + "℃ 高于上限 " + plain(c.tempMax()) + "℃");
            }
            if (c.tempMin() != null && low != null && low.compareTo(c.tempMin()) < 0) {
                out.add("最低温度 " + plain(low) + "℃ 低于下限 " + plain(c.tempMin()) + "℃");
            }
        } else if (c.monitor() && !pickup) {
            out.add("要求记录途中温度，但没有记录");
        }
        if (damaged) {
            out.add("到货异常" + (damageNote == null || damageNote.isBlank() ? "" : "：" + damageNote.trim()));
        }
        return out;
    }

    /** 列表里的温度，例如“18.5–24.0℃”。 */
    public static String reading(boolean monitored, BigDecimal low, BigDecimal high) {
        if (!monitored) {
            return "没有记录温度";
        }
        return low.compareTo(high) == 0 ? plain(low) + "℃" : plain(low) + "–" + plain(high) + "℃";
    }

    static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().scale() <= 0 ? v.setScale(0).toPlainString() : v.stripTrailingZeros().toPlainString();
    }
}
