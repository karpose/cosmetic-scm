package com.cosmetic.scm.common;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 审计日志里“改了什么”：逐项列出修改前后的值。
 * 只记“修改了物料”的话，事后看不出禁用标记、限用浓度、资质这些关键字段是谁在什么时候改的。
 */
public final class FieldDiff {

    private FieldDiff() {
    }

    /** 两个 Map 的键相同（标签 → 值）。返回“标签 旧值 → 新值；……”，没有变化时返回空字符串。 */
    public static String of(Map<String, ?> before, Map<String, ?> after) {
        List<String> changes = new ArrayList<>();
        after.forEach((label, value) -> {
            String was = text(before.get(label));
            String now = text(value);
            if (!Objects.equals(was, now)) {
                changes.add(label + " " + was + " → " + now);
            }
        });
        return String.join("；", changes);
    }

    private static String text(Object v) {
        if (v == null || "".equals(v)) {
            return "空";
        }
        if (v instanceof BigDecimal d) {
            // 数据库读出来是 0.5000，请求里是 0.5，不算变化
            return d.signum() == 0 ? "0" : d.stripTrailingZeros().toPlainString();
        }
        if (v instanceof Boolean b) {
            return b ? "是" : "否";
        }
        return v.toString();
    }
}
