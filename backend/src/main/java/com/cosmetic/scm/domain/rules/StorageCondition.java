package com.cosmetic.scm.domain.rules;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 贮存区域的温湿度判断（GMP 第五十六条留样贮存条件、第五十九条贮存和运输）。范围不内置，由质量部门给每个区域设定。
 * 读数等于上下限算合格；没设限度的项目不判断。超限的分组：和一次超限的第一条、最后一条记录相隔都不超过合并窗口的，算同一次。
 */
public final class StorageCondition {

    private StorageCondition() {
    }

    /** 温度（℃）和相对湿度（%）的上下限，都可以留空。 */
    public record Limits(BigDecimal tempMin, BigDecimal tempMax, BigDecimal rhMin, BigDecimal rhMax) {

        public boolean hasTemp() {
            return tempMin != null || tempMax != null;
        }

        public boolean hasRh() {
            return rhMin != null || rhMax != null;
        }

        /** 例如“温度 15–30℃，湿度 ≤75%”。 */
        public String text() {
            List<String> parts = new ArrayList<>();
            if (hasTemp()) {
                parts.add("温度 " + range(tempMin, tempMax, "℃"));
            }
            if (hasRh()) {
                parts.add("湿度 " + range(rhMin, rhMax, "%"));
            }
            return parts.isEmpty() ? "没有设定" : String.join("，", parts);
        }
    }

    /** 设定范围时的检查，没有问题返回 null。 */
    public static String checkLimits(Limits l) {
        if (!l.hasTemp() && !l.hasRh()) {
            return "请至少设定温度或湿度的一个上限或下限";
        }
        if (l.tempMin() != null && l.tempMax() != null && l.tempMin().compareTo(l.tempMax()) >= 0) {
            return "温度下限要低于上限";
        }
        if (l.rhMin() != null && l.rhMax() != null && l.rhMin().compareTo(l.rhMax()) >= 0) {
            return "湿度下限要低于上限";
        }
        return null;
    }

    /** 设了温度范围就要录温度，设了湿度范围就要录湿度。缺了返回说明，否则 null。 */
    public static String missingReading(Limits l, BigDecimal temp, BigDecimal rh) {
        if (temp == null && rh == null) {
            return "请填写温度或湿度";
        }
        if (l.hasTemp() && temp == null) {
            return "这个区域设了温度范围，请填写温度";
        }
        if (l.hasRh() && rh == null) {
            return "这个区域设了湿度范围，请填写相对湿度";
        }
        return null;
    }

    public static boolean tempOut(Limits l, BigDecimal temp) {
        return temp != null && (l.tempMax() != null && temp.compareTo(l.tempMax()) > 0 || l.tempMin() != null && temp.compareTo(l.tempMin()) < 0);
    }

    public static boolean rhOut(Limits l, BigDecimal rh) {
        return rh != null && (l.rhMax() != null && rh.compareTo(l.rhMax()) > 0 || l.rhMin() != null && rh.compareTo(l.rhMin()) < 0);
    }

    /** 超出范围的说明，在范围内返回空列表。 */
    public static List<String> violations(Limits l, BigDecimal temp, BigDecimal rh) {
        List<String> out = new ArrayList<>();
        if (temp != null) {
            if (l.tempMax() != null && temp.compareTo(l.tempMax()) > 0) {
                out.add("温度 " + plain(temp) + "℃ 高于上限 " + plain(l.tempMax()) + "℃");
            }
            if (l.tempMin() != null && temp.compareTo(l.tempMin()) < 0) {
                out.add("温度 " + plain(temp) + "℃ 低于下限 " + plain(l.tempMin()) + "℃");
            }
        }
        if (rh != null) {
            if (l.rhMax() != null && rh.compareTo(l.rhMax()) > 0) {
                out.add("湿度 " + plain(rh) + "% 高于上限 " + plain(l.rhMax()) + "%");
            }
            if (l.rhMin() != null && rh.compareTo(l.rhMin()) < 0) {
                out.add("湿度 " + plain(rh) + "% 低于下限 " + plain(l.rhMin()) + "%");
            }
        }
        return out;
    }

    /** 一次超限的时间段，existing 表示库里已有的那次（不是这次新建的）。 */
    public static final class Window {

        private LocalDateTime start;
        private LocalDateTime last;
        private final boolean existing;

        public Window(LocalDateTime start, LocalDateTime last, boolean existing) {
            this.start = start;
            this.last = last;
            this.existing = existing;
        }

        public LocalDateTime start() {
            return start;
        }

        public LocalDateTime last() {
            return last;
        }

        public boolean existing() {
            return existing;
        }

        boolean accepts(LocalDateTime t, Duration window) {
            return !t.isBefore(start.minus(window)) && !t.isAfter(last.plus(window));
        }

        void extend(LocalDateTime t) {
            if (t.isBefore(start)) {
                start = t;
            }
            if (t.isAfter(last)) {
                last = t;
            }
        }
    }

    /**
     * 把超限记录的时间分到各次超限：能并进已有的（或这批里前面新建的）就并进去，否则新建一次（追加到 windows 末尾）。
     * 返回每个时间对应的 windows 下标。times 要按时间先后排好。
     */
    public static List<Integer> assign(List<Window> windows, List<LocalDateTime> times, Duration window) {
        List<Integer> out = new ArrayList<>(times.size());
        for (LocalDateTime t : times) {
            int hit = -1;
            for (int i = 0; i < windows.size(); i++) {
                if (windows.get(i).accepts(t, window)) {
                    hit = i;
                    break;
                }
            }
            if (hit < 0) {
                windows.add(new Window(t, t, false));
                hit = windows.size() - 1;
            } else {
                windows.get(hit).extend(t);
            }
            out.add(hit);
        }
        return out;
    }

    /** from 到 to（含）里没有记录的日子，按日期先后。from 晚于 to 时返回空列表。 */
    public static List<LocalDate> missingDays(LocalDate from, LocalDate to, Set<LocalDate> recorded) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (!recorded.contains(d)) {
                out.add(d);
            }
        }
        return out;
    }

    /** 连续的日子合并成段，例如“11-03、11-05 至 11-07”，最多列 max 段。 */
    public static String daysText(List<LocalDate> days, int max) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < days.size()) {
            int j = i;
            while (j + 1 < days.size() && days.get(j + 1).equals(days.get(j).plusDays(1))) {
                j++;
            }
            parts.add(j == i ? days.get(i).toString() : days.get(i) + " 至 " + days.get(j));
            i = j + 1;
        }
        if (parts.size() <= max) {
            return String.join("、", parts);
        }
        return String.join("、", parts.subList(0, max)) + " 等 " + parts.size() + " 段";
    }

    private static String range(BigDecimal min, BigDecimal max, String unit) {
        if (min != null && max != null) {
            return plain(min) + "–" + plain(max) + unit;
        }
        return min != null ? "≥" + plain(min) + unit : "≤" + plain(max) + unit;
    }

    public static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }
}
