package com.cosmetic.scm.domain.rules;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * CAS 登记号：2–7 位数字-2 位数字-1 位校验位。校验位 = 从右往左第 i 位数字乘以 i 的和对 10 取余。
 * 只做格式和校验位检查，不判断这个号码对应什么物质。
 */
public final class CasNumber {

    private CasNumber() {
    }

    /** 去空格、把全角数字和连字符换成半角；格式或校验位不对返回空。 */
    public static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.trim().toCharArray()) {
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else if (c == '－' || c == '—' || c == '–' || c == '‐') {
                sb.append('-');
            } else if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        String s = sb.toString();
        if (!s.matches("\\d{2,7}-\\d{2}-\\d")) {
            return Optional.empty();
        }
        String digits = s.replace("-", "");
        int check = digits.charAt(digits.length() - 1) - '0';
        int sum = 0;
        for (int i = digits.length() - 2, w = 1; i >= 0; i--, w++) {
            sum += (digits.charAt(i) - '0') * w;
        }
        return sum % 10 == check ? Optional.of(s) : Optional.empty();
    }

    /** 一格里可能写了多个（分号、逗号、斜杠、顿号、空格分隔），只取有效的；“无”“—”之类的忽略。 */
    public static List<String> parseAll(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split("[;；,，/、\\s]+")) {
            normalize(part).ifPresent(out::add);
        }
        return new ArrayList<>(out);
    }
}
