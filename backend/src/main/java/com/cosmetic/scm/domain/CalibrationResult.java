package com.cosmetic.scm.domain;

/** 校准结论 */
public enum CalibrationResult {
    PASS("合格"),
    FAIL("不合格");

    private final String label;

    CalibrationResult(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
