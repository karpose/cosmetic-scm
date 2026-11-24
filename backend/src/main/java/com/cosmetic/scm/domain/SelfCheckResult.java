package com.cosmetic.scm.domain;

/** GMP 自查单项的结果 */
public enum SelfCheckResult {
    PENDING("未检查"),
    COMPLIANT("符合"),
    FLAWED("存在瑕疵"),
    NONCOMPLIANT("不符合"),
    NOT_APPLICABLE("不适用");

    private final String label;

    SelfCheckResult(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
