package com.cosmetic.scm.domain;

/** 试制结论 */
public enum TrialResult {
    PASS("通过"),
    ADJUST("需调整"),
    FAIL("不通过");

    private final String label;

    TrialResult(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
