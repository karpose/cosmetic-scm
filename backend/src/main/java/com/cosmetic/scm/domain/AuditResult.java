package com.cosmetic.scm.domain;

/** 供应商审核结论 */
public enum AuditResult {
    PASS("合格"),
    CONDITIONAL("有条件合格"),
    FAIL("不合格");

    private final String label;

    AuditResult(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
